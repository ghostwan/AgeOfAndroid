package com.ghostwan.ageofandroid.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldTest {

    private fun newWorld(seed: Long = 42L, opponents: Int = 1) =
        World(GameConfig(civ = Civ.FRANKS, opponents = opponents, difficulty = Difficulty.NORMAL, mapSize = 64, seed = seed))

    @Test
    fun `map generation gives every player a town centre, 3 villagers and a scout`() {
        val world = newWorld(opponents = 3)
        assertEquals(4, world.players.size)
        world.players.forEach { p ->
            assertEquals(1, world.buildings.count { it.owner == p.id && it.type == BuildingType.TOWN_CENTER })
            assertEquals(3, world.units.count { it.owner == p.id && it.type == UnitType.VILLAGER })
            assertEquals(1, world.units.count { it.owner == p.id && it.type == UnitType.SCOUT })
            assertEquals(5, p.popCap)
        }
        assertTrue(world.nodes.count { it.kind == NodeKind.TREE } > 100)
        assertTrue(world.nodes.any { it.kind == NodeKind.GOLD })
    }

    @Test
    fun `pathfinding goes around obstacles`() {
        val map = GameMap(20, 20)
        for (y in 0 until 15) map.occupant[map.idx(10, y)] = 999
        val path = map.findPath(2, 2, 15.5f, 2.5f) { x, y -> x == 15 && y == 2 }
        assertTrue(path.isNotEmpty())
        assertEquals(Pair(15, 2), path.last())
        // Every straight segment must be walkable.
        var px = 2.5f
        var py = 2.5f
        path.forEach { (x, y) ->
            assertTrue(map.lineWalkable(px, py, x + 0.5f, y + 0.5f))
            px = x + 0.5f
            py = y + 0.5f
        }
    }

    @Test
    fun `villager gathers wood and deposits it`() {
        val world = newWorld()
        val human = world.human
        val v = world.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        val tc = world.buildings.first { it.owner == 0 && it.type == BuildingType.TOWN_CENTER }
        val tree = world.nearestNode(NodeKind.TREE, tc.x, tc.y)
        assertNotNull(tree)
        val before = human.res[ResourceType.WOOD]
        world.commandGather(listOf(v), tree!!)
        world.forceAdvance(90f)
        assertTrue("wood should increase", human.res[ResourceType.WOOD] > before)
    }

    @Test
    fun `town centre trains a villager`() {
        val world = newWorld()
        val tc = world.buildings.first { it.owner == 0 && it.type == BuildingType.TOWN_CENTER }
        val before = world.units.count { it.owner == 0 }
        assertEquals(World.QueueResult.Ok, world.train(tc, UnitType.VILLAGER))
        assertEquals(150, world.human.res[ResourceType.FOOD])
        world.forceAdvance(UnitType.VILLAGER.trainTime + 0.5f)
        assertEquals(before + 1, world.units.count { it.owner == 0 })
    }

    @Test
    fun `cannot train beyond population cap`() {
        val world = newWorld()
        world.human.res.add(ResourceType.FOOD, 1000f)
        val tc = world.buildings.first { it.owner == 0 && it.type == BuildingType.TOWN_CENTER }
        repeat(3) { world.train(tc, UnitType.VILLAGER) }
        world.forceAdvance(UnitType.VILLAGER.trainTime * 3 + 2f)
        // 4 starting units + 1 trained = 5 = pop cap.
        assertEquals(5, world.units.count { it.owner == 0 })
    }

    @Test
    fun `building is constructed by a villager and adds population`() {
        val world = newWorld()
        val v = world.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        val tc = world.buildings.first { it.owner == 0 && it.type == BuildingType.TOWN_CENTER }
        var placed: Building? = null
        loop@ for (dy in -6..6) for (dx in -6..6) {
            placed = world.placeBuilding(0, BuildingType.HOUSE, tc.tx + dx, tc.ty + dy, listOf(v))
            if (placed != null) break@loop
        }
        assertNotNull(placed)
        world.forceAdvance(BuildingType.HOUSE.buildTime + 15f)
        assertTrue(placed!!.complete)
        assertEquals(10, world.human.popCap)
    }

    @Test
    fun `militia kills a villager`() {
        val world = newWorld()
        val enemyVillager = world.units.first { it.owner == 1 && it.type == UnitType.VILLAGER }
        val militia = world.spawnUnit(0, UnitType.MILITIA, enemyVillager.x + 1.5f, enemyVillager.y)
        world.commandAttack(listOf(militia), enemyVillager)
        world.forceAdvance(30f)
        assertTrue(!enemyVillager.alive || enemyVillager.hp < enemyVillager.maxHp)
    }

    @Test
    fun `age advancement works`() {
        val world = newWorld()
        world.human.res.add(ResourceType.FOOD, 500f)
        val tc = world.buildings.first { it.owner == 0 && it.type == BuildingType.TOWN_CENTER }
        assertEquals(World.QueueResult.Ok, world.advanceAge(tc))
        world.forceAdvance(Age.FEUDAL.researchTime + 1f)
        assertEquals(Age.FEUDAL, world.human.age)
    }

    @Test
    fun `AI develops its economy and army over time`() {
        for (seed in listOf(1L, 2L, 3L)) {
            val world = newWorld(seed = seed, opponents = 2)
            repeat(16) {
                world.forceAdvance(60f, step = 0.1f)
                // Keep the passive human alive so the AIs keep playing.
                world.buildings.filter { it.owner == 0 }.forEach { it.hp = it.maxHp }
            }
            world.players.filter { !it.isHuman }.forEach { ai ->
                val villagers = world.units.count { it.owner == ai.id && it.type == UnitType.VILLAGER }
                val buildings = world.buildings.count { it.owner == ai.id }
                println("seed=$seed ${ai.name}: age=${ai.age} vil=$villagers pop=${ai.pop}/${ai.popCap} buildings=$buildings res=${ResourceType.entries.map { ai.res[it] }}")
                assertTrue("AI ${ai.id} should have grown (villagers=$villagers)", villagers >= 10 || ai.defeated)
                assertTrue("AI ${ai.id} should reach Feudal", ai.age >= Age.FEUDAL || ai.defeated)
            }
        }
    }
}
