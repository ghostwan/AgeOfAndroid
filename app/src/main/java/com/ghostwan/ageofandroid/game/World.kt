package com.ghostwan.ageofandroid.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

data class GameConfig(
    val civ: Civ = Civ.FRANKS,
    val opponents: Int = 1,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val mapSize: Int = 64,
    val seed: Long = System.currentTimeMillis(),
)

data class GameEvent(val text: String, val x: Float = -1f, val y: Float = -1f, val alert: Boolean = false)

/** @param generate false when the world is about to be restored from a save (see [SaveGame]). */
class World(val config: GameConfig, generate: Boolean = true) {
    val rnd = Random(config.seed)
    val map = GameMap(config.mapSize, config.mapSize)
    val players = ArrayList<Player>()
    val units = ArrayList<GameUnit>()
    val buildings = ArrayList<Building>()
    val nodes = ArrayList<ResourceNode>()
    val projectiles = ArrayList<Projectile>()
    private val byId = HashMap<Int, Entity>()
    internal var nextId = 1
    var time = 0f
        internal set

    /** Events addressed to the human player (id 0). */
    val events = ArrayDeque<GameEvent>()

    /** Winner id once the game is over, null while it runs. */
    var winner: Int? = null
        private set

    // Fog of war for the human player.
    val visible = BooleanArray(map.width * map.height)
    val explored = BooleanArray(map.width * map.height)
    var revealAll = false
    private var fogTimer = 0f
    private var victoryTimer = 0f
    private var lastAttackAlert = -100f
    private var lastHousingAlert = -100f

    val ais = ArrayList<AiPlayer>()

    /**
     * Tiles A* may still expand during this tick. Big fights used to trigger dozens of full searches
     * in the same tick (visible freezes on phones); units over budget simply wait for the next tick.
     */
    private var pathBudget = PATH_BUDGET

    // Spatial buckets for unit separation.
    // One tile per bucket: units only push each other within ~0.6 tile, and bigger buckets made
    // packed armies compare every unit with dozens of far-away ones.
    private val bucketSize = 1
    private val bucketsW = (map.width + bucketSize - 1) / bucketSize
    private val buckets = Array(bucketsW * bucketsW) { ArrayList<GameUnit>(4) }
    private val usedBuckets = IntArray(bucketsW * bucketsW)
    private var usedBucketCount = 0

    init {
        if (generate) {
            MapGenerator(this).generate()
            finishSetup()
        }
    }

    /** Creates the AIs and derived state once players and entities are in place. */
    internal fun finishSetup() {
        players.filter { !it.isHuman }.forEach { ais += AiPlayer(this, it, config.difficulty) }
        updatePopulation()
        updateFog()
    }

    /** Re-inserts an entity read from a save, keeping its id and map occupancy. */
    internal fun restore(e: Entity) {
        byId[e.id] = e
        when (e) {
            is GameUnit -> units += e
            is Building -> {
                buildings += e
                if (!e.type.walkable) map.setOccupant(e.tx, e.ty, e.size, e.id)
            }
            is ResourceNode -> {
                nodes += e
                map.setOccupant(e.tx, e.ty, 1, e.id)
            }
        }
    }

    val human: Player get() = players[0]

    // ------------------------------------------------------------------ Entity management

    fun entity(id: Int): Entity? = byId[id]?.takeIf { it.alive }

    internal fun addPlayer(player: Player) {
        players += player
    }

    fun spawnUnit(owner: Int, type: UnitType, x: Float, y: Float): GameUnit {
        val u = GameUnit(nextId++, owner, type, x, y, maxHpOf(players[owner], type))
        units += u
        byId[u.id] = u
        return u
    }

    fun addNode(kind: NodeKind, tx: Int, ty: Int): ResourceNode? {
        if (!map.inBounds(tx, ty) || !map.passable(tx, ty)) return null
        val n = ResourceNode(nextId++, kind, tx, ty)
        nodes += n
        byId[n.id] = n
        map.setOccupant(tx, ty, 1, n.id)
        return n
    }

    fun addBuilding(owner: Int, type: BuildingType, tx: Int, ty: Int, complete: Boolean): Building {
        val p = players[owner]
        val b = Building(nextId++, owner, type, tx, ty, buildingMaxHp(p, type))
        if (complete) {
            b.progress = 1f
            b.hp = b.maxHp
        } else {
            b.hp = max(1f, b.maxHp * 0.1f)
        }
        if (type == BuildingType.FARM) b.food = farmFood(p)
        buildings += b
        byId[b.id] = b
        if (!type.walkable) {
            map.setOccupant(tx, ty, type.size, b.id)
            // Push units out of the footprint.
            for (u in units) {
                if (u.alive && b.contains(floor(u.x).toInt(), floor(u.y).toInt())) {
                    map.nearestPassable(floor(u.x).toInt(), floor(u.y).toInt())?.let { (px, py) ->
                        u.x = px + 0.5f
                        u.y = py + 0.5f
                        u.clearPath()
                    }
                }
            }
        }
        return b
    }

    // ------------------------------------------------------------------ Stats

    fun attackOf(p: Player, t: UnitType): Int {
        var a = t.attack
        val meleeLike = t.category == Category.INFANTRY || t.category == Category.CAVALRY
        if (meleeLike && p.has(Tech.FORGING)) a++
        if (meleeLike && p.has(Tech.IRON_CASTING)) a++
        if (t.category == Category.ARCHER && p.has(Tech.FLETCHING)) a++
        return a
    }

    fun rangeOf(p: Player, t: UnitType): Float {
        if (t.range <= 0f) return 0f
        var r = t.range
        if (t.category == Category.ARCHER && p.has(Tech.FLETCHING)) r += 1f
        if (t.category == Category.ARCHER && p.civ == Civ.BRITONS) r += 1f
        return r
    }

    fun meleeArmorOf(p: Player, t: UnitType): Int {
        var a = t.meleeArmor
        if ((t.category == Category.INFANTRY || t.category == Category.CAVALRY) && p.has(Tech.SCALE_MAIL)) a++
        if (t.category == Category.INFANTRY && p.civ == Civ.TEUTONS) a++
        return a
    }

    fun pierceArmorOf(p: Player, t: UnitType): Int {
        var a = t.pierceArmor
        if ((t.category == Category.INFANTRY || t.category == Category.CAVALRY) && p.has(Tech.SCALE_MAIL)) a++
        return a
    }

    fun speedOf(p: Player, t: UnitType): Float =
        if (t == UnitType.VILLAGER && p.has(Tech.WHEELBARROW)) t.speed * 1.1f else t.speed

    fun maxHpOf(p: Player, t: UnitType): Float =
        if (t.category == Category.CAVALRY && p.civ == Civ.FRANKS) t.maxHp * 1.2f else t.maxHp.toFloat()

    fun buildingMaxHp(p: Player, t: BuildingType): Float = if (p.has(Tech.MASONRY)) t.maxHp * 1.2f else t.maxHp.toFloat()

    fun buildingRange(p: Player, t: BuildingType): Float {
        var r = t.range
        if (p.has(Tech.FLETCHING)) r += 1f
        if (p.civ == Civ.BRITONS) r += 1f
        return r
    }

    fun buildingAttack(p: Player, t: BuildingType): Int = t.attack + if (p.has(Tech.FLETCHING)) 1 else 0

    fun farmFood(p: Player) = if (p.has(Tech.HORSE_COLLAR)) 350f else 250f

    fun gatherRate(p: Player, kind: NodeKind?): Float {
        var rate = when (kind) {
            null -> if (p.has(Tech.HORSE_COLLAR)) 0.46f else 0.42f // farm
            NodeKind.BERRIES -> 0.5f
            NodeKind.TREE -> if (p.has(Tech.DOUBLE_BIT_AXE)) 0.66f else 0.55f
            NodeKind.GOLD -> if (p.has(Tech.GOLD_MINING)) 0.57f else 0.5f
            NodeKind.STONE -> if (p.has(Tech.GOLD_MINING)) 0.57f else 0.5f
        }
        if (!p.isHuman) rate *= config.difficulty.gatherBonus
        return rate
    }

    fun isEnemy(a: Int, b: Int) = a != b && a != GAIA && b != GAIA

    fun canBuild(p: Player, type: BuildingType) = type != BuildingType.TOWN_CENTER || p.age >= Age.CASTLE

    // ------------------------------------------------------------------ Commands

    fun commandMove(group: List<GameUnit>, x: Float, y: Float) {
        val offsets = formationOffsets(group.size)
        group.forEachIndexed { i, u ->
            val (ox, oy) = offsets[i]
            u.task = Task.MOVE
            u.targetId = -1
            setDestinationPoint(u, x + ox, y + oy)
        }
    }

    fun commandStop(group: List<GameUnit>) {
        group.forEach {
            it.task = Task.IDLE
            it.targetId = -1
            it.clearPath()
        }
    }

    fun commandAttack(group: List<GameUnit>, target: Entity) {
        group.forEach { u ->
            if (u.owner == target.owner) return@forEach
            u.task = Task.ATTACK
            u.targetId = target.id
            u.clearPath()
            u.repathTimer = 0f
        }
    }

    fun commandGather(group: List<GameUnit>, target: Entity) {
        group.filter { it.type == UnitType.VILLAGER }.forEach { u ->
            u.task = Task.GATHER
            u.targetId = target.id
            u.clearPath()
            rememberGather(u, target)
        }
    }

    fun commandBuild(group: List<GameUnit>, b: Building) {
        group.filter { it.type == UnitType.VILLAGER }.forEach { u ->
            u.task = Task.BUILD
            u.targetId = b.id
            u.clearPath()
        }
    }

    fun commandReturn(group: List<GameUnit>, drop: Building? = null) {
        group.filter { it.type == UnitType.VILLAGER && it.carryAmount > 0 }.forEach { u ->
            u.task = Task.RETURN
            u.targetId = drop?.id ?: -1
            u.clearPath()
        }
    }

    /** Contextual "right-click" order, as on PC. */
    fun commandSmart(group: List<GameUnit>, x: Float, y: Float, target: Entity?) {
        if (group.isEmpty()) return
        val owner = group.first().owner
        when {
            target == null -> commandMove(group, x, y)
            target is ResourceNode -> {
                commandGather(group, target)
                commandMove(group.filter { it.type != UnitType.VILLAGER }, x, y)
            }
            target is Building && target.owner == owner -> {
                val villagers = group.filter { it.type == UnitType.VILLAGER }
                when {
                    !target.complete -> commandBuild(villagers, target)
                    target.type == BuildingType.FARM -> commandGather(villagers, target)
                    target.type.isDropSite -> {
                        villagers.forEach { v ->
                            if (v.carryAmount > 0 && v.carryType in target.type.dropSite) {
                                commandReturn(listOf(v), target)
                            } else {
                                commandMove(listOf(v), x, y)
                            }
                        }
                    }
                    else -> commandMove(villagers, x, y)
                }
                commandMove(group.filter { it.type != UnitType.VILLAGER }, x, y)
            }
            isEnemy(owner, target.owner) -> commandAttack(group, target)
            else -> commandMove(group, x, y)
        }
    }

    /** Validates & pays for a building placement, then sends [builders] to it. */
    fun placeBuilding(playerId: Int, type: BuildingType, tx: Int, ty: Int, builders: List<GameUnit>): Building? {
        val p = players[playerId]
        if (p.age < type.minAge || !canBuild(p, type)) return null
        if (!map.canPlace(tx, ty, type.size)) return null
        if (playerId == 0 && !revealAll && !isAreaExplored(tx, ty, type.size)) return null
        if (!p.res.pay(p.buildingCost(type))) return null
        val b = addBuilding(playerId, type, tx, ty, complete = false)
        commandBuild(builders, b)
        return b
    }

    fun isAreaExplored(tx: Int, ty: Int, size: Int): Boolean {
        for (y in ty until ty + size) for (x in tx until tx + size) {
            if (map.inBounds(x, y) && !explored[map.idx(x, y)]) return false
        }
        return true
    }

    fun canPlaceFor(playerId: Int, type: BuildingType, tx: Int, ty: Int): Boolean =
        map.canPlace(tx, ty, type.size) && (playerId != 0 || revealAll || isAreaExplored(tx, ty, type.size))

    sealed interface QueueResult {
        data object Ok : QueueResult
        data class Error(val reason: String) : QueueResult
    }

    fun train(b: Building, type: UnitType): QueueResult {
        val p = players[b.owner]
        return when {
            !b.complete -> QueueResult.Error("Bâtiment en construction")
            type.trainedAt != b.type -> QueueResult.Error("Impossible ici")
            p.age < type.minAge -> QueueResult.Error("Nécessite ${type.minAge.label}")
            b.queue.size >= MAX_QUEUE -> QueueResult.Error("File d'attente pleine")
            !p.res.pay(type.cost) -> QueueResult.Error("Ressources insuffisantes")
            else -> {
                b.queue += QueueItem(unit = type)
                QueueResult.Ok
            }
        }
    }

    fun research(b: Building, tech: Tech): QueueResult {
        val p = players[b.owner]
        return when {
            !b.complete -> QueueResult.Error("Bâtiment en construction")
            tech.researchedAt != b.type -> QueueResult.Error("Impossible ici")
            p.has(tech) || tech in p.researching -> QueueResult.Error("Déjà recherchée")
            p.age < tech.minAge -> QueueResult.Error("Nécessite ${tech.minAge.label}")
            b.queue.size >= MAX_QUEUE -> QueueResult.Error("File d'attente pleine")
            !p.res.pay(tech.cost) -> QueueResult.Error("Ressources insuffisantes")
            else -> {
                p.researching += tech
                b.queue += QueueItem(tech = tech)
                QueueResult.Ok
            }
        }
    }

    fun advanceAge(b: Building): QueueResult {
        val p = players[b.owner]
        val next = p.age.next ?: return QueueResult.Error("Âge maximal atteint")
        return when {
            b.type != BuildingType.TOWN_CENTER || !b.complete -> QueueResult.Error("Nécessite un forum")
            p.researchingAge -> QueueResult.Error("Déjà en cours")
            b.queue.size >= MAX_QUEUE -> QueueResult.Error("File d'attente pleine")
            !p.res.pay(next.cost) -> QueueResult.Error("Ressources insuffisantes")
            else -> {
                p.researchingAge = true
                b.queue += QueueItem(age = next)
                QueueResult.Ok
            }
        }
    }

    fun cancelQueue(b: Building, index: Int) {
        val item = b.queue.getOrNull(index) ?: return
        b.queue.removeAt(index)
        if (index == 0) b.queueProgress = 0f
        val p = players[b.owner]
        p.res.refund(item.cost)
        item.tech?.let { p.researching -= it }
        if (item.age != null) p.researchingAge = false
    }

    fun setRally(b: Building, x: Float, y: Float) {
        b.rallyX = x
        b.rallyY = y
    }

    /** Destroys a building that is still a foundation and refunds it (or deletes any own entity). */
    fun deleteEntity(e: Entity) {
        if (e is Building && !e.complete && e.progress < 0.01f) players[e.owner].res.refund(players[e.owner].buildingCost(e.type))
        kill(e, null)
    }

    // ------------------------------------------------------------------ Simulation

    fun update(dtRaw: Float) {
        if (winner != null) return
        val dt = min(dtRaw, 0.1f)
        time += dt
        pathBudget = PATH_BUDGET

        for (i in 0 until units.size) {
            val u = units[i]
            if (u.alive) updateUnit(u, dt)
        }
        separateUnits(dt)
        for (i in 0 until buildings.size) {
            val b = buildings[i]
            if (b.alive) updateBuilding(b, dt)
        }
        updateProjectiles(dt)

        units.removeAll { !it.alive }
        buildings.removeAll { !it.alive }
        nodes.removeAll { !it.alive }
        projectiles.removeAll { !it.alive }

        updatePopulation()
        ais.forEach { it.update(dt) }

        fogTimer -= dt
        if (fogTimer <= 0f) {
            fogTimer = 0.2f
            updateFog()
        }
        victoryTimer -= dt
        if (victoryTimer <= 0f) {
            victoryTimer = 1f
            checkVictory()
        }
    }

    private fun updatePopulation() {
        players.forEach {
            it.pop = 0
            it.popCap = 0
        }
        units.forEach { if (it.alive) players[it.owner].pop++ }
        buildings.forEach { if (it.alive && it.complete) players[it.owner].popCap += it.type.popProvided }
        players.forEach { it.popCap = min(Player.MAX_POP, it.popCap) }
    }

    private fun updateUnit(u: GameUnit, dt: Float) {
        u.cooldown = max(0f, u.cooldown - dt)
        u.moving = false
        val p = players[u.owner]
        when (u.task) {
            Task.IDLE -> {
                if (u.type.isMilitary) autoAcquire(u, dt)
            }
            Task.MOVE -> {
                if (followPath(u, dt)) {
                    u.task = Task.IDLE
                    u.scanTimer = 0f
                }
            }
            Task.GATHER -> updateGather(u, p, dt)
            Task.RETURN -> updateReturn(u, p, dt)
            Task.BUILD -> updateBuild(u, p, dt)
            Task.ATTACK -> updateAttack(u, p, dt)
        }
        if (u.moving) u.animTime += dt
        // Stuck detection: if we should be moving but don't make progress, repath.
        if (u.moving) {
            u.stuckTimer += dt
            if (u.stuckTimer > 1.2f) {
                if (dist(u.x, u.y, u.lastProgressX, u.lastProgressY) < 0.15f) u.clearPath()
                u.stuckTimer = 0f
                u.lastProgressX = u.x
                u.lastProgressY = u.y
            }
        }
    }

    private fun autoAcquire(u: GameUnit, dt: Float) {
        u.scanTimer -= dt
        if (u.scanTimer > 0f) return
        u.scanTimer = 0.5f
        findEnemyNear(u.owner, u.x, u.y, u.type.los.toFloat())?.let {
            u.task = Task.ATTACK
            u.targetId = it.id
            u.clearPath()
        }
    }

    fun findEnemyNear(owner: Int, x: Float, y: Float, radius: Float, includeBuildings: Boolean = true): Entity? {
        var best: Entity? = null
        var bestD = radius
        for (o in units) {
            if (!o.alive || !isEnemy(owner, o.owner)) continue
            val d = dist(x, y, o.x, o.y)
            if (d <= bestD) {
                bestD = d
                best = o
            }
        }
        if (best != null || !includeBuildings) return best
        for (b in buildings) {
            if (!b.alive || !isEnemy(owner, b.owner)) continue
            val d = b.distanceTo(x, y)
            if (d <= bestD) {
                bestD = d
                best = b
            }
        }
        return best
    }

    private fun interactReach(target: Entity) = if (target is Building && target.type.walkable) 0.35f else 0.85f

    private fun updateGather(u: GameUnit, p: Player, dt: Float) {
        val target = entity(u.targetId)
        val node = target as? ResourceNode
        val farm = (target as? Building)?.takeIf { it.type == BuildingType.FARM && it.complete && it.owner == u.owner }
        if (node == null && farm == null) {
            if (!retargetGather(u)) {
                if (u.carryAmount > 0) {
                    u.task = Task.RETURN
                    u.targetId = -1
                } else {
                    u.task = Task.IDLE
                }
            }
            return
        }
        val resType = node?.kind?.resource ?: ResourceType.FOOD
        if (u.carryAmount > 0 && u.carryType != resType) {
            // Drop foreign resource first.
            u.task = Task.RETURN
            u.targetId = -1
            u.clearPath()
            return
        }
        val reach = interactReach(target)
        if (target.distanceTo(u.x, u.y) <= reach + 0.15f) {
            u.clearPath()
            faceTowards(u, target.x, target.y)
            val amount = gatherRate(p, node?.kind) * dt
            u.carryType = resType
            u.moving = false
            u.animTime += dt
            if (node != null) {
                val taken = min(amount, node.amount)
                node.amount -= taken
                u.carryAmount += taken
                if (node.amount <= 0.01f) kill(node, null)
            } else if (farm != null) {
                val taken = min(amount, farm.food)
                farm.food -= taken
                u.carryAmount += taken
                if (farm.food <= 0.01f) reseedOrRemoveFarm(farm, p)
            }
            if (u.carryAmount >= p.carryCapacity()) {
                u.carryAmount = p.carryCapacity()
                u.task = Task.RETURN
                u.targetId = -1
                u.clearPath()
            }
        } else {
            moveToEntity(u, target, reach, dt)
        }
    }

    private fun reseedOrRemoveFarm(farm: Building, p: Player) {
        val cost = p.buildingCost(BuildingType.FARM)
        if (p.res.pay(cost)) {
            farm.food = farmFood(p)
        } else {
            kill(farm, null)
            if (p.isHuman) pushEvent("Une ferme est épuisée (bois insuffisant pour la ressemer)", farm.x, farm.y)
        }
    }

    private fun rememberGather(u: GameUnit, target: Entity) {
        when (target) {
            is ResourceNode -> {
                u.lastGatherKind = target.kind
                u.lastGatherFarm = false
            }
            is Building -> {
                u.lastGatherKind = null
                u.lastGatherFarm = true
            }
            else -> Unit
        }
        u.lastGatherX = target.x
        u.lastGatherY = target.y
    }

    /** Looks for another node of the same kind close to where the villager was working. */
    private fun retargetGather(u: GameUnit): Boolean {
        if (u.lastGatherFarm) {
            val farm = buildings.filter { it.alive && it.owner == u.owner && it.type == BuildingType.FARM && it.complete && !farmTaken(it, u) }
                .minByOrNull { dist(it.x, it.y, u.lastGatherX, u.lastGatherY) }
                ?.takeIf { dist(it.x, it.y, u.lastGatherX, u.lastGatherY) < 10f } ?: return false
            u.targetId = farm.id
            u.clearPath()
            return true
        }
        val kind = u.lastGatherKind ?: return false
        val n = nearestNode(kind, u.lastGatherX, u.lastGatherY, 10f) ?: return false
        u.targetId = n.id
        u.clearPath()
        return true
    }

    fun farmTaken(farm: Building, except: GameUnit? = null) =
        units.any { it !== except && it.alive && it.task == Task.GATHER && it.targetId == farm.id }

    fun nearestNode(kind: NodeKind, x: Float, y: Float, maxDist: Float = Float.MAX_VALUE): ResourceNode? {
        var best: ResourceNode? = null
        var bestD = maxDist
        for (n in nodes) {
            if (!n.alive || n.kind != kind) continue
            val d = dist(x, y, n.x, n.y)
            if (d < bestD) {
                bestD = d
                best = n
            }
        }
        return best
    }

    fun nearestDropSite(owner: Int, type: ResourceType, x: Float, y: Float): Building? =
        buildings.filter { it.alive && it.owner == owner && it.complete && type in it.type.dropSite }
            .minByOrNull { it.distanceTo(x, y) }

    private fun updateReturn(u: GameUnit, p: Player, dt: Float) {
        val type = u.carryType
        if (type == null || u.carryAmount <= 0f) {
            resumeGather(u)
            return
        }
        val drop = (entity(u.targetId) as? Building)?.takeIf { it.owner == u.owner && it.complete && type in it.type.dropSite }
            ?: nearestDropSite(u.owner, type, u.x, u.y)
        if (drop == null) {
            u.task = Task.IDLE
            return
        }
        u.targetId = drop.id
        if (drop.distanceTo(u.x, u.y) <= 1.0f) {
            p.res.add(type, u.carryAmount)
            p.gathered[type.ordinal] += u.carryAmount
            u.carryAmount = 0f
            u.carryType = null
            u.clearPath()
            resumeGather(u)
        } else {
            moveToEntity(u, drop, 0.85f, dt)
        }
    }

    private fun resumeGather(u: GameUnit) {
        u.task = Task.GATHER
        u.targetId = -1
        u.clearPath()
        // Prefer the last node if still there.
        val again = if (u.lastGatherFarm) {
            buildings.firstOrNull { it.alive && it.type == BuildingType.FARM && it.x == u.lastGatherX && it.y == u.lastGatherY && it.owner == u.owner }
        } else {
            nodes.firstOrNull { it.alive && it.x == u.lastGatherX && it.y == u.lastGatherY }
        }
        if (again != null) {
            u.targetId = again.id
        } else if (!retargetGather(u)) {
            u.task = Task.IDLE
        }
    }

    private fun updateBuild(u: GameUnit, p: Player, dt: Float) {
        val b = entity(u.targetId) as? Building
        if (b == null || b.owner != u.owner) {
            u.task = Task.IDLE
            return
        }
        if (b.complete) {
            afterBuild(u, b)
            return
        }
        if (b.distanceTo(u.x, u.y) <= 1.0f) {
            u.clearPath()
            faceTowards(u, b.x, b.y)
            u.animTime += dt
            val delta = dt / b.type.buildTime
            val before = b.progress
            b.progress = min(1f, b.progress + delta)
            b.hp = min(b.maxHp, b.hp + b.maxHp * 0.9f * (b.progress - before))
            if (b.complete) {
                b.hp = max(b.hp, b.maxHp * 0.99f)
                if (b.type == BuildingType.WONDER) {
                    b.wonderTimer = WONDER_TIME
                    pushEvent(
                        if (b.owner == 0) "Votre merveille est terminée ! Défendez-la ${WONDER_TIME.toInt()}s" else "${players[b.owner].name} a terminé une merveille !",
                        b.x, b.y, alert = b.owner != 0,
                    )
                } else if (b.owner == 0) {
                    pushEvent("${b.type.label} terminé(e)", b.x, b.y)
                }
                afterBuild(u, b)
            }
        } else {
            moveToEntity(u, b, 0.85f, dt)
        }
    }

    private fun afterBuild(u: GameUnit, b: Building) {
        u.task = Task.IDLE
        u.targetId = -1
        // Help on another foundation nearby.
        val other = buildings.filter { it.alive && it.owner == u.owner && !it.complete && it.distanceTo(u.x, u.y) < 8f }
            .minByOrNull { it.distanceTo(u.x, u.y) }
        if (other != null) {
            commandBuild(listOf(u), other)
            return
        }
        when {
            b.type == BuildingType.FARM && !farmTaken(b, u) -> commandGather(listOf(u), b)
            b.type.isDropSite && b.type != BuildingType.TOWN_CENTER -> {
                val kinds = NodeKind.entries.filter { it.resource in b.type.dropSite }
                kinds.mapNotNull { nearestNode(it, b.x, b.y, 8f) }.minByOrNull { dist(it.x, it.y, b.x, b.y) }
                    ?.let { commandGather(listOf(u), it) }
            }
        }
    }

    private fun updateAttack(u: GameUnit, p: Player, dt: Float) {
        val target = entity(u.targetId)
        if (target == null || target is ResourceNode || !isEnemy(u.owner, target.owner)) {
            u.task = Task.IDLE
            u.targetId = -1
            u.clearPath()
            u.scanTimer = 0f
            return
        }
        val range = rangeOf(p, u.type)
        val reach = if (range > 0f) {
            range
        } else if (target is GameUnit) {
            u.type.radius + target.type.radius + 0.25f
        } else {
            0.6f
        }
        // When sieging a building, fight back against nearby enemy units first.
        if (target is Building && u.type.category != Category.SIEGE) {
            u.scanTimer -= dt
            if (u.scanTimer <= 0f) {
                u.scanTimer = 0.6f
                val threat = findEnemyNear(u.owner, u.x, u.y, max(3f, range + 0.5f), includeBuildings = false)
                if (threat != null) {
                    u.targetId = threat.id
                    u.clearPath()
                    return
                }
            }
        }
        val d = target.distanceTo(u.x, u.y)
        if (d <= reach + 0.1f) {
            u.clearPath()
            faceTowards(u, target.x, target.y)
            if (u.cooldown <= 0f) {
                u.cooldown = u.type.reload
                u.animTime = 0f
                val dmg = computeDamage(p, u.type, target)
                if (range > 0f) {
                    projectiles += Projectile(u.x, u.y - 0.2f, target.id, u.owner, dmg)
                } else {
                    applyDamage(target, dmg, u.owner)
                }
            }
        } else {
            // Chasing a moving target: repath periodically.
            u.repathTimer -= dt
            if (target is GameUnit && u.repathTimer <= 0f) {
                u.repathTimer = 0.8f
                u.clearPath()
            }
            moveToEntity(u, target, if (range > 0f) range else reach, dt)
        }
    }

    fun computeDamage(p: Player, t: UnitType, target: Entity): Float {
        val atk = attackOf(p, t)
        val ranged = t.range > 0f
        val (armor, cat) = when (target) {
            is GameUnit -> {
                val tp = players[target.owner]
                Pair(if (ranged) pierceArmorOf(tp, target.type) else meleeArmorOf(tp, target.type), target.type.category)
            }
            is Building -> Pair(if (ranged) BUILDING_PIERCE_ARMOR else 0, Category.BUILDING)
            else -> Pair(0, Category.BUILDING)
        }
        return (max(1, atk - armor) + (t.bonus[cat] ?: 0)).toFloat()
    }

    fun applyDamage(target: Entity, dmg: Float, fromOwner: Int) {
        if (!target.alive) return
        target.hp -= dmg
        if (target.owner == 0 && fromOwner != 0 && time - lastAttackAlert > 15f) {
            lastAttackAlert = time
            pushEvent("Vous êtes attaqué !", target.x, target.y, alert = true)
        }
        // Units under attack fight back if military and idle-ish.
        if (target is GameUnit && target.type.isMilitary && (target.task == Task.IDLE || target.task == Task.MOVE && target.path.isEmpty())) {
            target.scanTimer = 0f
        }
        ais.firstOrNull { it.player.id == target.owner }?.onAttacked(target, fromOwner)
        if (target.hp <= 0f) kill(target, fromOwner)
    }

    private fun kill(e: Entity, by: Int?) {
        if (!e.alive) return
        e.alive = false
        when (e) {
            is GameUnit -> {
                players[e.owner].unitsLost++
                if (by != null && by >= 0) players[by].unitsKilled++
            }
            is Building -> {
                if (!e.type.walkable) map.setOccupant(e.tx, e.ty, e.size, -1)
                // Refund queued items.
                while (e.queue.isNotEmpty()) cancelQueue(e, e.queue.size - 1)
                if (by != null && by >= 0) players[by].buildingsRazed++
                if (e.owner == 0 && by != null) pushEvent("${e.type.label} détruit(e) !", e.x, e.y, alert = true)
            }
            is ResourceNode -> map.setOccupant(e.tx, e.ty, 1, -1)
        }
    }

    private fun updateBuilding(b: Building, dt: Float) {
        if (!b.complete) return
        val p = players[b.owner]
        if (b.type == BuildingType.WONDER && b.wonderTimer > 0f) {
            b.wonderTimer -= dt
            if (b.wonderTimer <= 0f) {
                winner = b.owner
                return
            }
        }
        // Defensive fire.
        if (b.type.attack > 0) {
            b.cooldown -= dt
            if (b.cooldown <= 0f) {
                val range = buildingRange(p, b.type)
                val enemy = findEnemyNear(b.owner, b.x, b.y, range + b.size / 2f, includeBuildings = false) as? GameUnit
                if (enemy != null) {
                    b.cooldown = 2f
                    val armor = pierceArmorOf(players[enemy.owner], enemy.type)
                    val dmg = max(1, buildingAttack(p, b.type) - armor).toFloat()
                    projectiles += Projectile(b.x, b.y - 1f, enemy.id, b.owner, dmg)
                } else {
                    b.cooldown = 0.5f
                }
            }
        }
        // Production queue.
        val item = b.queue.firstOrNull() ?: return
        if (item.unit != null && p.pop >= p.popCap) {
            if (p.isHuman && time - lastHousingAlert > 20f) {
                lastHousingAlert = time
                pushEvent(if (p.popCap >= Player.MAX_POP) "Population maximale atteinte" else "Population maximale : construisez des maisons !", alert = true)
            }
            return
        }
        b.queueProgress += dt
        if (b.queueProgress >= item.time) {
            b.queueProgress = 0f
            b.queue.removeAt(0)
            when {
                item.unit != null -> {
                    spawnFromBuilding(b, item.unit)
                    p.pop++
                }
                item.tech != null -> {
                    p.techs += item.tech
                    p.researching -= item.tech
                    if (item.tech == Tech.MASONRY) {
                        buildings.filter { it.owner == p.id }.forEach {
                            it.hp *= 1.2f
                            it.buildMaxHp = buildingMaxHp(p, it.type)
                        }
                    }
                    if (p.isHuman) pushEvent("Recherche terminée : ${item.tech.label}")
                }
                item.age != null -> {
                    p.age = item.age
                    p.researchingAge = false
                    pushEvent(
                        if (p.isHuman) "Vous entrez dans l'${item.age.label.lowercase()} !" else "${p.name} entre dans l'${item.age.label.lowercase()}",
                        alert = !p.isHuman,
                    )
                }
            }
        }
    }

    private fun spawnFromBuilding(b: Building, type: UnitType) {
        val hasRally = b.rallyX >= 0f
        val aimX = if (hasRally) b.rallyX else b.tx + b.size + 0.5f
        val aimY = if (hasRally) b.rallyY else b.ty + b.size + 0.5f
        // Find the free tile around the footprint closest to the rally point.
        var best: Pair<Int, Int>? = null
        var bestD = Float.MAX_VALUE
        for (y in b.ty - 1..b.ty + b.size) for (x in b.tx - 1..b.tx + b.size) {
            if (b.contains(x, y) || !map.passable(x, y)) continue
            val d = dist(x + 0.5f, y + 0.5f, aimX, aimY)
            if (d < bestD) {
                bestD = d
                best = Pair(x, y)
            }
        }
        val spot = best ?: map.nearestPassable(b.tx + b.size, b.ty + b.size) ?: return
        val u = spawnUnit(b.owner, type, spot.first + 0.5f, spot.second + 0.5f)
        if (hasRally) {
            val rallyTarget = entityAtTile(floor(b.rallyX).toInt(), floor(b.rallyY).toInt())
            if (type == UnitType.VILLAGER && rallyTarget != null) {
                commandSmart(listOf(u), b.rallyX, b.rallyY, rallyTarget)
            } else {
                commandMove(listOf(u), b.rallyX, b.rallyY)
            }
        }
        if (b.owner == 0 && type == UnitType.VILLAGER && !hasRally) {
            // nothing: idle villager button will help find it
        }
    }

    fun entityAtTile(tx: Int, ty: Int): Entity? {
        if (!map.inBounds(tx, ty)) return null
        val occ = map.occupant[map.idx(tx, ty)]
        if (occ >= 0) return entity(occ)
        return buildings.firstOrNull { it.alive && it.type.walkable && it.contains(tx, ty) }
    }

    private fun updateProjectiles(dt: Float) {
        for (pr in projectiles) {
            val target = entity(pr.targetId)
            if (target == null) {
                pr.alive = false
                continue
            }
            val tx = target.x
            val ty = target.y - 0.2f
            val d = dist(pr.x, pr.y, tx, ty)
            val step = pr.speed * dt
            if (d <= step) {
                pr.alive = false
                applyDamage(target, pr.damage, pr.damageFrom)
            } else {
                pr.x += (tx - pr.x) / d * step
                pr.y += (ty - pr.y) / d * step
                val total = dist(pr.startX, pr.startY, tx, ty)
                pr.progress = if (total > 0f) 1f - d / total else 1f
            }
        }
    }

    // ------------------------------------------------------------------ Movement

    private fun formationOffsets(n: Int): List<Pair<Float, Float>> {
        if (n <= 1) return listOf(Pair(0f, 0f))
        val res = ArrayList<Pair<Float, Float>>()
        val side = Math.ceil(sqrt(n.toDouble())).toInt()
        val spacing = 0.7f
        for (i in 0 until n) {
            val gx = i % side - (side - 1) / 2f
            val gy = i / side - (side - 1) / 2f
            res += Pair(gx * spacing, gy * spacing)
        }
        return res
    }

    internal fun setDestinationPoint(u: GameUnit, x: Float, y: Float) {
        var tx = floor(x).toInt().coerceIn(0, map.width - 1)
        var ty = floor(y).toInt().coerceIn(0, map.height - 1)
        var fx = x.coerceIn(0.1f, map.width - 0.1f)
        var fy = y.coerceIn(0.1f, map.height - 0.1f)
        if (!map.passable(tx, ty)) {
            val np = map.nearestPassable(tx, ty) ?: return
            tx = np.first
            ty = np.second
            fx = tx + 0.5f
            fy = ty + 0.5f
        }
        u.destX = fx
        u.destY = fy
        if (map.lineWalkable(u.x, u.y, fx, fy)) {
            u.path = listOf(Waypoint(fx, fy))
            u.pathIndex = 0
            return
        }
        val sx = floor(u.x).toInt()
        val sy = floor(u.y).toInt()
        val tiles = map.findPath(sx, sy, tx + 0.5f, ty + 0.5f) { cx, cy -> cx == tx && cy == ty }
        val wps = ArrayList<Waypoint>(tiles.size + 1)
        tiles.forEach { wps += Waypoint(it.first + 0.5f, it.second + 0.5f) }
        val reached = tiles.isEmpty() && sx == tx && sy == ty || tiles.lastOrNull()?.let { it.first == tx && it.second == ty } == true
        if (wps.isNotEmpty()) wps.removeAt(wps.size - 1)
        if (reached) {
            wps += Waypoint(fx, fy)
        } else if (tiles.isNotEmpty()) {
            val last = tiles.last()
            wps += Waypoint(last.first + 0.5f, last.second + 0.5f)
        }
        u.path = wps
        u.pathIndex = 0
    }

    /** Moves along the path toward [target] until within [reach]. Computes the path lazily. */
    private fun moveToEntity(u: GameUnit, target: Entity, reach: Float, dt: Float) {
        if (u.path.isEmpty() || u.pathIndex >= u.path.size) {
            if (u.repathTimer > 0f && u.path.isNotEmpty()) {
                u.repathTimer -= dt
                return
            }
            // Nothing in the way: walk straight there, no search needed (the common case in fights).
            val (ax, ay) = approachPoint(target, u.x, u.y, reach)
            if (dist(u.x, u.y, ax, ay) < DIRECT_RANGE && map.lineWalkable(u.x, u.y, ax, ay)) {
                u.path = listOf(Waypoint(ax, ay))
                u.pathIndex = 0
                u.repathTimer = 0.5f
                followPath(u, dt)
                return
            }
            if (pathBudget <= 0) return // try again next tick
            val sx = floor(u.x).toInt()
            val sy = floor(u.y).toInt()
            val tiles = map.findPath(sx, sy, target.x, target.y, maxNodes = min(MAX_PATH_NODES, pathBudget)) { cx, cy ->
                target.distanceTo(cx + 0.5f, cy + 0.5f) <= reach
            }
            pathBudget -= map.lastExpanded
            if (tiles.isEmpty()) {
                if (target.distanceTo(sx + 0.5f, sy + 0.5f) <= reach + 0.5f) {
                    // Already on a goal tile: step straight toward the target.
                    stepTowards(u, target.x, target.y, dt, stopAt = reach * 0.9f, target = target)
                } else {
                    // Unreachable target.
                    u.task = Task.IDLE
                    u.targetId = -1
                }
                return
            }
            u.path = tiles.map { Waypoint(it.first + 0.5f, it.second + 0.5f) }
            u.pathIndex = 0
            u.repathTimer = 0.5f
        }
        followPath(u, dt)
    }

    /** A point within [reach] of [target], on the side facing (ux, uy). */
    private fun approachPoint(target: Entity, ux: Float, uy: Float, reach: Float): Pair<Float, Float> {
        val (cx, cy) = when (target) {
            is Building -> Pair(
                ux.coerceIn(target.tx.toFloat(), (target.tx + target.size).toFloat()),
                uy.coerceIn(target.ty.toFloat(), (target.ty + target.size).toFloat()),
            )
            is ResourceNode -> Pair(ux.coerceIn(target.tx.toFloat(), target.tx + 1f), uy.coerceIn(target.ty.toFloat(), target.ty + 1f))
            else -> Pair(target.x, target.y)
        }
        val d = dist(ux, uy, cx, cy)
        if (d < 0.001f) return Pair(cx, cy)
        val back = min(reach * 0.7f, d)
        return Pair(cx + (ux - cx) / d * back, cy + (uy - cy) / d * back)
    }

    private fun stepTowards(u: GameUnit, x: Float, y: Float, dt: Float, stopAt: Float, target: Entity) {
        if (target.distanceTo(u.x, u.y) <= stopAt) return
        val d = dist(u.x, u.y, x, y)
        if (d < 0.01f) return
        val step = min(d, speedOf(players[u.owner], u.type) * dt)
        val nx = u.x + (x - u.x) / d * step
        val ny = u.y + (y - u.y) / d * step
        if (map.passable(nx, ny) || target is Building && target.type.walkable) {
            u.x = nx
            u.y = ny
            u.moving = true
            u.facingLeft = x < u.x
        }
    }

    /** Returns true when the end of the path has been reached. */
    private fun followPath(u: GameUnit, dt: Float): Boolean {
        if (u.pathIndex >= u.path.size) return true
        var remaining = speedOf(players[u.owner], u.type) * dt
        while (remaining > 0f && u.pathIndex < u.path.size) {
            val wp = u.path[u.pathIndex]
            val d = dist(u.x, u.y, wp.x, wp.y)
            if (d <= remaining) {
                u.x = wp.x
                u.y = wp.y
                remaining -= d
                u.pathIndex++
            } else {
                // Screen-space facing: in isometric view, +x goes right/down and +y goes left/down.
                u.facingLeft = (wp.x - u.x) - (wp.y - u.y) < 0
                u.x += (wp.x - u.x) / d * remaining
                u.y += (wp.y - u.y) / d * remaining
                remaining = 0f
            }
            u.moving = true
        }
        return u.pathIndex >= u.path.size
    }

    private fun faceTowards(u: GameUnit, x: Float, y: Float) {
        u.facingLeft = (x - u.x) - (y - u.y) < 0
    }

    private fun separateUnits(dt: Float) {
        for (k in 0 until usedBucketCount) buckets[usedBuckets[k]].clear()
        usedBucketCount = 0
        for (u in units) {
            if (!u.alive) continue
            val bx = (u.x / bucketSize).toInt().coerceIn(0, bucketsW - 1)
            val by = (u.y / bucketSize).toInt().coerceIn(0, bucketsW - 1)
            val b = buckets[by * bucketsW + bx]
            if (b.isEmpty()) usedBuckets[usedBucketCount++] = by * bucketsW + bx
            b += u
        }
        for (u in units) {
            if (!u.alive) continue
            val bx = (u.x / bucketSize).toInt().coerceIn(0, bucketsW - 1)
            val by = (u.y / bucketSize).toInt().coerceIn(0, bucketsW - 1)
            var pushX = 0f
            var pushY = 0f
            for (oy in -1..1) for (ox in -1..1) {
                val cx = bx + ox
                val cy = by + oy
                if (cx < 0 || cy < 0 || cx >= bucketsW || cy >= bucketsW) continue
                for (o in buckets[cy * bucketsW + cx]) {
                    if (o === u || !o.alive) continue
                    val minD = (u.type.radius + o.type.radius) * 0.9f
                    var dx = u.x - o.x
                    var dy = u.y - o.y
                    var d = sqrt(dx * dx + dy * dy)
                    if (d >= minD) continue
                    if (d < 0.001f) {
                        dx = ((u.id * 31) % 7 - 3).toFloat()
                        dy = ((u.id * 17) % 7 - 3).toFloat()
                        d = max(0.01f, sqrt(dx * dx + dy * dy))
                    }
                    val f = (minD - d) / minD
                    pushX += dx / d * f
                    pushY += dy / d * f
                }
            }
            if (pushX == 0f && pushY == 0f) continue
            // Busy workers are pushed less so they don't drift off their spot.
            val strength = if (u.moving) 1.2f else 0.8f
            val nx = u.x + pushX * strength * dt
            val ny = u.y + pushY * strength * dt
            if (map.passable(nx, ny) || isOnWalkableBuilding(nx, ny)) {
                u.x = nx.coerceIn(0.05f, map.width - 0.05f)
                u.y = ny.coerceIn(0.05f, map.height - 0.05f)
            }
        }
    }

    private fun isOnWalkableBuilding(x: Float, y: Float): Boolean {
        val tx = floor(x).toInt()
        val ty = floor(y).toInt()
        return map.inBounds(tx, ty) && map.terrainAt(tx, ty).walkable && map.occupant[map.idx(tx, ty)] == -1
    }

    // ------------------------------------------------------------------ Fog & victory

    fun updateFog() {
        java.util.Arrays.fill(visible, false)
        if (revealAll) {
            java.util.Arrays.fill(visible, true)
            java.util.Arrays.fill(explored, true)
            return
        }
        for (u in units) if (u.alive && u.owner == 0) reveal(u.x, u.y, u.type.los.toFloat())
        for (b in buildings) if (b.alive && b.owner == 0) reveal(b.x, b.y, b.type.los + b.size / 2f)
    }

    private fun reveal(cx: Float, cy: Float, r: Float) {
        val r2 = r * r
        val x0 = max(0, (cx - r).toInt())
        val x1 = min(map.width - 1, (cx + r).toInt())
        val y0 = max(0, (cy - r).toInt())
        val y1 = min(map.height - 1, (cy + r).toInt())
        for (y in y0..y1) for (x in x0..x1) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r2) {
                val i = map.idx(x, y)
                visible[i] = true
                explored[i] = true
            }
        }
    }

    fun isVisible(x: Float, y: Float): Boolean {
        val tx = floor(x).toInt()
        val ty = floor(y).toInt()
        return map.inBounds(tx, ty) && visible[map.idx(tx, ty)]
    }

    fun isExplored(tx: Int, ty: Int) = map.inBounds(tx, ty) && explored[map.idx(tx, ty)]

    /** Whether the human player can currently see this entity. */
    fun canSee(e: Entity): Boolean = when {
        e.owner == 0 -> true
        e is GameUnit -> isVisible(e.x, e.y)
        e is Building -> isAnyTileExplored(e.tx, e.ty, e.size)
        e is ResourceNode -> isExplored(e.tx, e.ty)
        else -> false
    }

    private fun isAnyTileExplored(tx: Int, ty: Int, size: Int): Boolean {
        for (y in ty until ty + size) for (x in tx until tx + size) if (isExplored(x, y)) return true
        return false
    }

    private fun checkVictory() {
        for (p in players) {
            if (p.defeated) continue
            val hasBuilding = buildings.any { it.alive && it.owner == p.id && it.type != BuildingType.FARM }
            val hasUnit = units.any { it.alive && it.owner == p.id }
            if (!hasBuilding && !hasUnit) {
                p.defeated = true
                buildings.filter { it.owner == p.id }.forEach { kill(it, null) }
                pushEvent(if (p.isHuman) "Vous avez été vaincu." else "${p.name} a été vaincu !", alert = true)
            }
        }
        if (human.defeated) {
            winner = players.firstOrNull { !it.defeated }?.id ?: -1
        } else if (players.none { !it.isHuman && !it.defeated }) {
            winner = 0
        }
    }

    fun pushEvent(text: String, x: Float = -1f, y: Float = -1f, alert: Boolean = false) {
        events.addLast(GameEvent(text, x, y, alert))
        while (events.size > 20) events.removeFirst()
    }

    /** Debug/test helper. */
    fun forceAdvance(seconds: Float, step: Float = 0.05f) {
        var t = 0f
        while (t < seconds && winner == null) {
            update(step)
            t += step
        }
    }

    companion object {
        const val MAX_QUEUE = 5
        const val MAX_PATH_NODES = 6000
        const val PATH_BUDGET = 4000
        const val DIRECT_RANGE = 14f
        const val BUILDING_PIERCE_ARMOR = 6
        const val WONDER_TIME = 180f

        fun angleOffset(angle: Double, d: Float) = Pair((cos(angle) * d).toFloat(), (sin(angle) * d).toFloat())
        fun chebyshev(ax: Int, ay: Int, bx: Int, by: Int) = max(abs(ax - bx), abs(ay - by))
    }
}
