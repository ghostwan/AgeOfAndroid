package com.ghostwan.ageofandroid.game

import kotlin.math.abs
import kotlin.math.max

/**
 * A simple but complete rule-based opponent: booms villagers, keeps houses up, builds drop sites
 * near resources, advances through the ages, trains an army and sends attack waves.
 */
class AiPlayer(private val world: World, val player: Player, private val difficulty: Difficulty) {
    private var thinkTimer = 0.5f + player.id * 0.13f
    private var nextAttackTime = difficulty.firstAttack
    private var attackWaveSize = when (difficulty) {
        Difficulty.EASY -> 6
        Difficulty.NORMAL -> 8
        Difficulty.HARD -> 10
    }
    private var defendUntil = 0f
    private var rebalanceTimer = 6f
    private var threatX = 0f
    private var threatY = 0f

    private val tc: Building?
        get() = world.buildings.firstOrNull { it.alive && it.owner == player.id && it.type == BuildingType.TOWN_CENTER }

    fun onAttacked(target: Entity, attacker: Int) {
        if (attacker < 0 || attacker == player.id) return
        val home = tc ?: return
        if (dist(target.x, target.y, home.x, home.y) < 18f) {
            defendUntil = world.time + 8f
            threatX = target.x
            threatY = target.y
        }
        // Villagers band together against small raids, like players do with "militia" villagers.
        if (target is GameUnit && target.type == UnitType.VILLAGER && world.time - lastVillagerDefense > 3f) {
            lastVillagerDefense = world.time
            val enemy = world.findEnemyNear(player.id, target.x, target.y, 3f, includeBuildings = false) as? GameUnit ?: return
            if (enemy.type == UnitType.RAM) return
            val helpers = world.units.filter {
                it.alive && it.owner == player.id && it.type == UnitType.VILLAGER && dist(it.x, it.y, enemy.x, enemy.y) < 6f
            }
            if (helpers.size >= 3) world.commandAttack(helpers, enemy)
        }
    }

    private var lastVillagerDefense = -10f

    fun update(dt: Float) {
        if (player.defeated) return
        thinkTimer -= dt
        if (thinkTimer > 0f) return
        thinkTimer = 1f
        val home = tc
        val myUnits = world.units.filter { it.alive && it.owner == player.id }
        val villagers = myUnits.filter { it.type == UnitType.VILLAGER }
        val army = myUnits.filter { it.type.isMilitary }
        val myBuildings = world.buildings.filter { it.alive && it.owner == player.id }

        if (home == null) {
            // Try to rebuild a town centre when possible.
            if (player.age >= Age.CASTLE && villagers.isNotEmpty()) tryBuild(BuildingType.TOWN_CENTER, villagers, player.startX.toFloat(), player.startY.toFloat(), myBuildings)
            attack(army)
            return
        }

        economy(home, villagers, myBuildings)
        buildings(home, villagers, myBuildings)
        military(myBuildings, army)
        if (world.time < defendUntil) defend(army) else if (world.time >= nextAttackTime && army.size >= attackWaveSize) {
            attack(army)
            nextAttackTime = world.time + 120f
            attackWaveSize += 3
        }
    }

    // ------------------------------------------------------------------ Economy

    private fun economy(home: Building, villagers: List<GameUnit>, myBuildings: List<Building>) {
        val res = player.res
        // Villagers
        val target = difficulty.villagerTarget + player.age.ordinal * 4
        val queuedVillagers = home.queue.count { it.unit == UnitType.VILLAGER }
        if (villagers.size + queuedVillagers < target && queuedVillagers < 2 && !player.researchingAge) {
            world.train(home, UnitType.VILLAGER)
        }
        // Age up
        val next = player.age.next
        if (next != null && !player.researchingAge && res.canAfford(next.cost)) {
            val ready = when (next) {
                Age.FEUDAL -> villagers.size >= 14
                Age.CASTLE -> villagers.size >= 20 && myBuildings.any { it.type.military }
                Age.IMPERIAL -> villagers.size >= 26 && difficulty != Difficulty.EASY
                else -> false
            }
            if (ready) world.advanceAge(home)
        }
        // Economic techs
        if (player.age >= Age.FEUDAL) {
            for (b in myBuildings) {
                if (!b.complete || b.queue.isNotEmpty()) continue
                for (t in b.type.techs) {
                    if (!player.has(t) && t !in player.researching && player.age >= t.minAge &&
                        res.canAfford(Cost(t.cost.food + 100, t.cost.wood + 50, t.cost.gold, t.cost.stone))
                    ) {
                        world.research(b, t)
                        break
                    }
                }
            }
        }

        // Assign idle villagers to the most needed resource.
        val workers = HashMap<ResourceType, MutableList<GameUnit>>()
        villagers.forEach { v -> gatherType(v)?.let { workers.getOrPut(it) { ArrayList() } += v } }
        val counts = IntArray(4) { workers[ResourceType.entries[it]]?.size ?: 0 }
        val weights = when (player.age) {
            Age.DARK -> floatArrayOf(0.55f, 0.35f, 0.06f, 0.04f)
            Age.FEUDAL -> floatArrayOf(0.45f, 0.3f, 0.18f, 0.07f)
            else -> floatArrayOf(0.42f, 0.26f, 0.24f, 0.08f)
        }
        // Adapt to the stockpile: stop over-gathering what we already have plenty of.
        ResourceType.entries.forEach { t ->
            val cap = if (t == ResourceType.FOOD || t == ResourceType.WOOD) 700 else 500
            if (res[t] > cap) weights[t.ordinal] *= 0.3f
        }
        if (res[ResourceType.FOOD] < 150) weights[ResourceType.FOOD.ordinal] *= 1.5f
        val wSum = weights.sum()
        fun neediest(): ResourceType {
            val total = max(1, counts.sum())
            return ResourceType.entries.maxBy { weights[it.ordinal] / wSum - counts[it.ordinal].toFloat() / total }
        }
        for (v in villagers.filter { it.task == Task.IDLE }) {
            val type = neediest()
            if (assignGatherer(v, type, home, myBuildings)) counts[type.ordinal]++
            else if (assignGatherer(v, ResourceType.WOOD, home, myBuildings)) counts[ResourceType.WOOD.ordinal]++
        }
        // Rebalance one worker at a time from the most over-staffed resource.
        rebalanceTimer -= 1f
        if (rebalanceTimer <= 0f && counts.sum() >= 4) {
            rebalanceTimer = 6f
            val total = counts.sum().toFloat()
            val need = neediest()
            val over = ResourceType.entries.minBy { weights[it.ordinal] / wSum - counts[it.ordinal] / total }
            val deficit = (weights[need.ordinal] / wSum) * total - counts[need.ordinal]
            if (need != over && deficit >= 1.5f) {
                val mover = workers[over]?.firstOrNull { it.carryAmount < 1f } ?: workers[over]?.firstOrNull()
                if (mover != null) assignGatherer(mover, need, home, myBuildings)
            }
        }
    }

    private fun gatherType(v: GameUnit): ResourceType? = when (v.task) {
        Task.GATHER, Task.RETURN -> when (val t = world.entity(v.targetId)) {
            is ResourceNode -> t.kind.resource
            is Building -> if (t.type == BuildingType.FARM) ResourceType.FOOD else v.carryType
            else -> v.carryType
        }
        else -> null
    }

    private fun assignGatherer(v: GameUnit, type: ResourceType, home: Building, myBuildings: List<Building>): Boolean {
        if (type == ResourceType.FOOD) {
            val berries = world.nearestNode(NodeKind.BERRIES, home.x, home.y, 14f)
            if (berries != null) {
                world.commandGather(listOf(v), berries)
                return true
            }
            val freeFarm = myBuildings.firstOrNull { it.type == BuildingType.FARM && it.complete && !world.farmTaken(it) }
            if (freeFarm != null) {
                world.commandGather(listOf(v), freeFarm)
                return true
            }
            if (player.res[ResourceType.WOOD] >= 60) {
                return tryBuild(BuildingType.FARM, listOf(v), home.x, home.y, myBuildings, margin = 0, minDist = 2f)
            }
            return false
        }
        val kind = when (type) {
            ResourceType.WOOD -> NodeKind.TREE
            ResourceType.GOLD -> NodeKind.GOLD
            ResourceType.STONE -> NodeKind.STONE
            else -> return false
        }
        // Prefer nodes near an existing drop site, otherwise near home.
        val drops = myBuildings.filter { it.complete && type in it.type.dropSite }
        val candidates = drops.mapNotNull { world.nearestNode(kind, it.x, it.y, 25f) }
        val node = candidates.minByOrNull { n -> drops.minOf { it.distanceTo(n.x, n.y) } }
            ?: world.nearestNode(kind, home.x, home.y, 30f) ?: return false
        world.commandGather(listOf(v), node)
        return true
    }

    // ------------------------------------------------------------------ Construction

    private fun buildings(home: Building, villagers: List<GameUnit>, myBuildings: List<Building>) {
        if (villagers.isEmpty()) return
        val underConstruction = myBuildings.count { !it.complete }
        if (underConstruction >= 2) return
        val res = player.res
        fun builder(): GameUnit? = villagers.filter { it.task != Task.BUILD }.minByOrNull { dist(it.x, it.y, home.x, home.y) }

        // Houses
        val housesBuilding = myBuildings.any { it.type == BuildingType.HOUSE && !it.complete }
        if (player.popCap < Player.MAX_POP && player.popCap - player.pop <= 3 + player.age.ordinal && !housesBuilding && res[ResourceType.WOOD] >= 25) {
            builder()?.let { tryBuild(BuildingType.HOUSE, listOf(it), home.x, home.y, myBuildings, minDist = 4f) }
            return
        }
        // Drop sites near busy resources.
        for ((type, kind) in listOf(BuildingType.LUMBER_CAMP to NodeKind.TREE, BuildingType.MINING_CAMP to NodeKind.GOLD, BuildingType.MILL to NodeKind.BERRIES)) {
            val workers = villagers.filter { v ->
                val n = world.entity(v.targetId) as? ResourceNode
                n != null && (n.kind == kind || (type == BuildingType.MINING_CAMP && n.kind == NodeKind.STONE))
            }
            if (workers.size < 2) continue
            val node = world.entity(workers.first().targetId) as? ResourceNode ?: continue
            val nearestDrop = world.nearestDropSite(player.id, kind.resource, node.x, node.y)
            if (nearestDrop == null || nearestDrop.distanceTo(node.x, node.y) > 6f) {
                if (res.canAfford(player.buildingCost(type))) {
                    tryBuild(type, listOf(workers.first()), node.x, node.y, myBuildings, minDist = 1.5f)
                    return
                }
            }
        }
        val count = { t: BuildingType -> myBuildings.count { it.type == t } }
        val wants = buildList {
            if (villagers.size >= 10) add(BuildingType.BARRACKS)
            if (player.age >= Age.FEUDAL) {
                add(BuildingType.ARCHERY_RANGE)
                add(BuildingType.BLACKSMITH)
                if (difficulty != Difficulty.EASY) add(BuildingType.STABLE)
                if (count(BuildingType.WATCH_TOWER) < 1 + player.age.ordinal && difficulty != Difficulty.EASY) add(BuildingType.WATCH_TOWER)
            }
            if (player.age >= Age.CASTLE) {
                add(BuildingType.SIEGE_WORKSHOP)
                if (count(BuildingType.BARRACKS) < 2) add(BuildingType.BARRACKS)
            }
            if (player.age >= Age.IMPERIAL) add(BuildingType.WONDER)
        }
        for (t in wants) {
            val have = count(t)
            val limit = if (t == BuildingType.WATCH_TOWER) 1 + player.age.ordinal else if (t == BuildingType.BARRACKS && player.age >= Age.CASTLE) 2 else 1
            if (have >= limit) continue
            if (player.age < t.minAge || !res.canAfford(player.buildingCost(t))) continue
            val crew = villagers.filter { it.task != Task.BUILD }.sortedBy { dist(it.x, it.y, home.x, home.y) }
                .take(if (t == BuildingType.WONDER) 8 else 2)
            if (crew.isEmpty()) return
            val towardCentre = Pair((world.map.width / 2f - home.x) * 0.25f, (world.map.height / 2f - home.y) * 0.25f)
            val ax = if (t == BuildingType.WATCH_TOWER) home.x + towardCentre.first else home.x
            val ay = if (t == BuildingType.WATCH_TOWER) home.y + towardCentre.second else home.y
            tryBuild(t, crew, ax, ay, myBuildings, minDist = 4f)
            return
        }
    }

    private fun tryBuild(
        type: BuildingType,
        crew: List<GameUnit>,
        ax: Float,
        ay: Float,
        myBuildings: List<Building>,
        margin: Int = 1,
        minDist: Float = 3f,
    ): Boolean {
        val spot = findSpot(type, ax, ay, margin, minDist) ?: return false
        return world.placeBuilding(player.id, type, spot.first, spot.second, crew) != null
    }

    /** Spiral search for a free spot with a free margin around it so the base stays walkable. */
    private fun findSpot(type: BuildingType, ax: Float, ay: Float, margin: Int, minDist: Float): Pair<Int, Int>? {
        val s = type.size
        val cx = ax.toInt()
        val cy = ay.toInt()
        for (r in 0..16) {
            val candidates = ArrayList<Pair<Int, Int>>()
            for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                candidates += Pair(cx + dx - s / 2, cy + dy - s / 2)
            }
            candidates.shuffle(world.rnd)
            for ((tx, ty) in candidates) {
                if (dist(tx + s / 2f, ty + s / 2f, ax, ay) < minDist) continue
                if (world.map.canPlace(tx - margin, ty - margin, s + 2 * margin)) return Pair(tx, ty)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ Military

    private fun military(myBuildings: List<Building>, army: List<GameUnit>) {
        val res = player.res
        // Keep a reserve for villagers and age-ups once the economy is going.
        val villagers = world.units.count { it.alive && it.owner == player.id && it.type == UnitType.VILLAGER }
        val next = player.age.next
        val savingForAge = next != null && !player.researchingAge && next < Age.IMPERIAL && villagers >= 12 + player.age.ordinal * 6
        val reserveFood = if (savingForAge) next!!.cost.food else 50
        val reserveGold = if (savingForAge) next!!.cost.gold else 0
        for (b in myBuildings) {
            if (!b.complete || !b.type.military || b.queue.size >= 2) continue
            val options = b.type.trains.filter { player.age >= it.minAge }
            if (options.isEmpty()) continue
            val choice = when {
                b.type == BuildingType.BARRACKS && player.age >= Age.FEUDAL && world.rnd.nextFloat() < 0.4f -> UnitType.SPEARMAN
                b.type == BuildingType.STABLE && player.age >= Age.CASTLE -> UnitType.KNIGHT
                b.type == BuildingType.SIEGE_WORKSHOP && army.count { it.type == UnitType.RAM } >= 2 -> null
                else -> options.first()
            } ?: continue
            val c = choice.cost
            if (res[ResourceType.FOOD] - c.food >= reserveFood && res[ResourceType.GOLD] - c.gold >= reserveGold && res.canAfford(c)) {
                world.train(b, choice)
            }
        }
        // Gather the army near home when idle.
        val home = tc ?: return
        val rallyX = home.x + (world.map.width / 2f - home.x) * 0.15f
        val rallyY = home.y + (world.map.height / 2f - home.y) * 0.15f
        army.filter { it.task == Task.IDLE && dist(it.x, it.y, rallyX, rallyY) > 8f && dist(it.x, it.y, home.x, home.y) < 20f }
            .takeIf { it.isNotEmpty() }?.let { world.commandMove(it, rallyX, rallyY) }
    }

    private fun defend(army: List<GameUnit>) {
        val enemy = world.findEnemyNear(player.id, threatX, threatY, 10f) ?: return
        army.filter { it.task != Task.ATTACK || world.entity(it.targetId) is Building }.forEach {
            world.commandAttack(listOf(it), enemy)
        }
    }

    private fun attack(army: List<GameUnit>) {
        if (army.isEmpty()) return
        val enemies = world.players.filter { !it.defeated && it.id != player.id }
        if (enemies.isEmpty()) return
        // Focus the human a bit more often so the player gets action.
        val victim = enemies.firstOrNull { it.isHuman && world.rnd.nextFloat() < 0.6f } ?: enemies[world.rnd.nextInt(enemies.size)]
        val ax = army.map { it.x }.average().toFloat()
        val ay = army.map { it.y }.average().toFloat()
        val targets = world.buildings.filter { it.alive && it.owner == victim.id }
        val hasRams = army.any { it.type == UnitType.RAM }
        // Raid weak targets first (villagers, economic buildings), the town centre only with rams.
        val target = targets.filter { it.type == BuildingType.TOWN_CENTER && hasRams }.minByOrNull { it.distanceTo(ax, ay) }
            ?: world.units.filter { it.alive && it.owner == victim.id && dist(it.x, it.y, victim.startX.toFloat(), victim.startY.toFloat()) < 22f }
                .minByOrNull { dist(it.x, it.y, ax, ay) }
            ?: targets.filter { it.type.attack == 0 }.minByOrNull { it.distanceTo(ax, ay) }
            ?: targets.minByOrNull { it.distanceTo(ax, ay) }
            ?: world.units.filter { it.alive && it.owner == victim.id }.minByOrNull { dist(it.x, it.y, ax, ay) }
            ?: return
        if (player.id != 0 && target.owner == 0) world.pushEvent("Une armée ennemie approche !", ax, ay, alert = true)
        // Rams go for buildings, the rest move in and auto-acquire on the way via attack orders.
        world.commandAttack(army, target)
    }
}
