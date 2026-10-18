package com.ghostwan.ageofandroid.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapTypesTest {
    /** Tiles reachable by land from (sx, sy), ignoring trees and mines (they can be cleared). */
    private fun landReach(map: GameMap, sx: Int, sy: Int, free: Boolean = false): BooleanArray {
        val seen = BooleanArray(map.width * map.height)
        val queue = ArrayDeque<Int>()
        queue += map.idx(sx, sy)
        seen[map.idx(sx, sy)] = true
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val x = i % map.width + dx
                val y = i / map.width + dy
                if (!map.inBounds(x, y) || seen[map.idx(x, y)] || !map.terrainAt(x, y).walkable || free && !map.passable(x, y)) continue
                seen[map.idx(x, y)] = true
                queue += map.idx(x, y)
            }
        }
        return seen
    }

    @Test
    fun everyBaseReachesTheOthersOnEveryMapType() {
        for (type in MapType.entries) for (size in listOf(56, 72, 96)) for (seed in 1L..6L) {
            val w = World(GameConfig(opponents = 3, mapSize = size, seed = seed, mapType = type))
            val reach = landReach(w.map, w.players[0].startX, w.players[0].startY)
            for (p in w.players) {
                assertTrue("$type $size seed $seed: ${p.name} cut off", reach[w.map.idx(p.startX, p.startY)])
            }
            // Every base keeps its starting resources.
            for (p in w.players) {
                for (kind in listOf(NodeKind.GOLD, NodeKind.STONE, NodeKind.BERRIES)) {
                    assertTrue("$type $size seed $seed: no $kind near ${p.name}", w.nearestNode(kind, p.startX + 0.5f, p.startY + 0.5f, 18f) != null)
                }
            }
        }
    }

    @Test
    fun obstaclesAndFishMatchTheMapType() {
        fun count(type: MapType, t: Terrain) = (1L..4L).sumOf { seed ->
            World(GameConfig(opponents = 1, mapSize = 72, seed = seed, mapType = type)).map.terrain.count { it == t }
        }
        assertTrue(count(MapType.HIGHLANDS, Terrain.MOUNTAIN) > 200)
        assertTrue(count(MapType.CANYONS, Terrain.CANYON) > 200)
        assertEquals(0, count(MapType.ARABIA, Terrain.MOUNTAIN) + count(MapType.ARABIA, Terrain.CANYON))
        assertTrue(count(MapType.LAKES, Terrain.WATER) > count(MapType.ARABIA, Terrain.WATER) * 2)

        // Grands lacs: fish a short walk from every base.
        for (seed in 1L..6L) {
            val w = World(GameConfig(opponents = 3, mapSize = 72, seed = seed, mapType = MapType.LAKES))
            for (p in w.players) {
                assertTrue("seed $seed: no fish near ${p.name}", w.nearestNode(NodeKind.FISH, p.startX + 0.5f, p.startY + 0.5f, 18f) != null)
            }
            assertTrue(w.nodes.filter { it.kind == NodeKind.FISH }.all { w.map.terrainAt(it.tx, it.ty) == Terrain.WATER })
        }
    }

    @Test
    fun villagersFishFromTheShore() {
        val w = World(GameConfig(opponents = 1, mapSize = 72, seed = 3L, mapType = MapType.LAKES))
        w.ais.clear()
        val me = w.players[0]
        val v = w.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        val v0 = w.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        val reach = landReach(w.map, v0.x.toInt(), v0.y.toInt(), free = true)
        // Closest fish with a reachable land tile next to it.
        val fish = w.nodes.filter { n ->
            n.kind == NodeKind.FISH && listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).any { (dx, dy) ->
                w.map.inBounds(n.tx + dx, n.ty + dy) && reach[w.map.idx(n.tx + dx, n.ty + dy)]
            }
        }.minBy { dist(it.x, it.y, me.startX.toFloat(), me.startY.toFloat()) }
        val food = me.res.exact(ResourceType.FOOD)
        w.commandSmart(listOf(v), fish.x, fish.y, fish)
        assertEquals(Task.GATHER, v.task)
        w.forceAdvance(150f)
        assertTrue("villager brought no fish back", me.res.exact(ResourceType.FOOD) > food + 10f)
        assertTrue(fish.amount < NodeKind.FISH.amount)
    }

    @Test
    fun mapTypeIsSaved() {
        val w = World(GameConfig(opponents = 1, mapSize = 56, seed = 2L, mapType = MapType.CANYONS))
        val bytes = java.io.ByteArrayOutputStream().also { SaveGame.write(w, it) }.toByteArray()
        val loaded = SaveGame.read(bytes.inputStream())
        assertEquals(MapType.CANYONS, loaded.config.mapType)
        assertTrue(loaded.map.terrain.contentEquals(w.map.terrain))
    }
}
