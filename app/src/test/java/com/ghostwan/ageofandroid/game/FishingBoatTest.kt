package com.ghostwan.ageofandroid.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FishingBoatTest {
    private fun lakesWorld(): World {
        val w = World(GameConfig(opponents = 1, mapSize = 72, seed = 3L, mapType = MapType.LAKES))
        w.ais.clear()
        w.revealAll = true
        w.human.res.add(ResourceType.WOOD, 1000f)
        return w
    }

    /** Free land tiles the player's villagers can walk to. */
    private fun reachable(w: World): BooleanArray {
        val v = w.units.first { it.owner == w.viewer && it.type == UnitType.VILLAGER }
        val seen = BooleanArray(w.map.width * w.map.height)
        val queue = ArrayDeque<Int>()
        queue += w.map.idx(v.x.toInt(), v.y.toInt())
        seen[queue.first()] = true
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val x = i % w.map.width + dx
                val y = i / w.map.width + dy
                if (!w.map.passable(x, y) || seen[w.map.idx(x, y)]) continue
                seen[w.map.idx(x, y)] = true
                queue += w.map.idx(x, y)
            }
        }
        return seen
    }

    /** A valid dock spot, reachable by the villagers, as close as possible to the town centre. */
    private fun dockSpot(w: World): Pair<Int, Int> {
        val p = w.human
        val reach = reachable(w)
        var best: Pair<Int, Int>? = null
        for (y in 0 until w.map.height) for (x in 0 until w.map.width) {
            if (!w.canPlaceFor(p.id, BuildingType.DOCK, x, y)) continue
            val bank = (x - 1..x + 2).any { bx -> (y - 1..y + 2).any { by -> w.map.inBounds(bx, by) && reach[w.map.idx(bx, by)] } }
            if (!bank) continue
            if (best == null || dist(x.toFloat(), y.toFloat(), p.startX.toFloat(), p.startY.toFloat()) <
                dist(best.first.toFloat(), best.second.toFloat(), p.startX.toFloat(), p.startY.toFloat())
            ) best = Pair(x, y)
        }
        return best!!
    }

    @Test
    fun docksGoInTheWaterAgainstTheBank() {
        val w = lakesWorld()
        val p = w.human
        assertFalse("dock on land", w.canPlaceFor(p.id, BuildingType.DOCK, p.startX + 3, p.startY + 3))
        val (x, y) = dockSpot(w)
        for (ty in y until y + 2) for (tx in x until x + 2) assertEquals(Terrain.WATER, w.map.terrainAt(tx, ty))
        assertFalse("house on water", w.canPlaceFor(p.id, BuildingType.HOUSE, x, y))
    }

    @Test
    fun boatsFishAndUnloadAtTheDock() {
        val w = lakesWorld()
        val p = w.human
        val builders = w.units.filter { it.owner == p.id && it.type == UnitType.VILLAGER }
        val (x, y) = dockSpot(w)
        val dock = w.placeBuilding(p.id, BuildingType.DOCK, x, y, builders)
        assertNotNull(dock)
        dock!!
        w.forceAdvance(60f)
        assertTrue("dock not built", dock.complete)

        assertEquals(World.QueueResult.Ok, w.train(dock, UnitType.FISHING_BOAT))
        w.forceAdvance(UnitType.FISHING_BOAT.trainTime + 1f)
        val boat = w.units.single { it.type == UnitType.FISHING_BOAT }
        assertEquals(Terrain.WATER, w.map.terrainAt(boat.x.toInt(), boat.y.toInt()))
        assertFalse(boat.type.isMilitary)

        // Nearest fish the boat can sail to (same lake).
        val fish = w.nodes.filter { it.kind == NodeKind.FISH }.minBy { dist(it.x, it.y, dock.x, dock.y) }
        val food = p.res.exact(ResourceType.FOOD)
        w.commandSmart(listOf(boat), fish.x, fish.y, fish)
        assertEquals(Task.GATHER, boat.task)
        w.forceAdvance(120f)
        assertTrue("boat brought no fish back", p.res.exact(ResourceType.FOOD) > food + 30f)
        // It never left the water.
        assertEquals(Terrain.WATER, w.map.terrainAt(boat.x.toInt(), boat.y.toInt()))

        // Boats ignore land orders they can't follow, and can't gather wood.
        val tree = w.nodes.first { it.kind == NodeKind.TREE }
        assertFalse(w.canGather(boat, tree))
    }

    @Test
    fun villagersCantReachDeepFishAndMeleeCantReachBoats() {
        val w = lakesWorld()
        val p = w.human
        val v = w.units.first { it.owner == p.id && it.type == UnitType.VILLAGER }
        val deep = w.nodes.firstOrNull { n ->
            n.kind == NodeKind.FISH && (-1..1).all { dy -> (-1..1).all { dx -> !w.map.passable(n.tx + dx, n.ty + dy) } }
        }
        if (deep != null) {
            assertFalse(w.canGather(v, deep))
            w.commandSmart(listOf(v), deep.x, deep.y, deep)
            assertTrue(w.events.any { it.text.contains("construisez un port") })
        }
        val boat = w.spawnUnit(1, UnitType.FISHING_BOAT, 0f, 0f)
        val militia = w.spawnUnit(p.id, UnitType.MILITIA, 1f, 1f)
        val archer = w.spawnUnit(p.id, UnitType.ARCHER, 1f, 1f)
        assertFalse(w.canAttack(militia, boat))
        assertTrue(w.canAttack(archer, boat))
        assertFalse(w.canAttack(boat, militia))
    }
}
