package com.ghostwan.ageofandroid.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class SaveGameTest {

    private fun roundTrip(world: World): World {
        val out = ByteArrayOutputStream()
        SaveGame.write(world, out)
        return SaveGame.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `a saved game is restored identically and keeps playing`() {
        val world = World(GameConfig(civ = Civ.BRITONS, opponents = 2, difficulty = Difficulty.HARD, mapSize = 56, seed = 11L))
        val tc = world.buildings.first { it.owner == 0 && it.type == BuildingType.TOWN_CENTER }
        world.train(tc, UnitType.VILLAGER)
        val villager = world.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        world.commandGather(listOf(villager), world.nearestNode(NodeKind.TREE, tc.x, tc.y)!!)
        world.forceAdvance(5 * 60f, step = 0.1f)
        world.train(tc, UnitType.VILLAGER)

        val loaded = roundTrip(world)

        assertEquals(world.config, loaded.config)
        assertEquals(world.time, loaded.time, 0.001f)
        assertEquals(world.players.size, loaded.players.size)
        world.players.zip(loaded.players).forEach { (a, b) ->
            assertEquals(a.name, b.name)
            assertEquals(a.civ, b.civ)
            assertEquals(a.age, b.age)
            assertEquals(a.techs, b.techs)
            assertEquals(a.pop, b.pop)
            assertEquals(a.popCap, b.popCap)
            ResourceType.entries.forEach { assertEquals(a.res.exact(it), b.res.exact(it), 0.001f) }
        }
        assertEquals(world.units.map { Triple(it.id, it.type, it.task) }, loaded.units.map { Triple(it.id, it.type, it.task) })
        assertEquals(world.buildings.map { Triple(it.id, it.type, it.progress) }, loaded.buildings.map { Triple(it.id, it.type, it.progress) })
        assertEquals(world.buildings.map { it.queue.size }, loaded.buildings.map { it.queue.size })
        assertEquals(world.nodes.size, loaded.nodes.size)
        assertTrue(world.map.occupant.contentEquals(loaded.map.occupant))
        assertTrue(world.explored.contentEquals(loaded.explored))
        assertEquals(world.ais.size, loaded.ais.size)

        // The restored game keeps running: units work, the AI keeps growing.
        val woodBefore = loaded.human.res[ResourceType.WOOD]
        loaded.forceAdvance(3 * 60f, step = 0.1f)
        assertTrue(loaded.time > world.time + 170f)
        assertTrue(loaded.human.res[ResourceType.WOOD] > woodBefore || loaded.human.defeated)
        assertTrue(loaded.ais.all { ai -> loaded.units.count { it.owner == ai.player.id } > 6 || ai.player.defeated })
    }

    @Test
    fun `save file info and atomic write`() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try {
            val file = File(dir, "save.bin")
            val world = World(GameConfig(opponents = 1, mapSize = 56, seed = 3L))
            world.forceAdvance(20f)
            SaveGame.save(world, file)
            val info = SaveGame.info(file)
            assertNotNull(info)
            assertEquals(world.config, info!!.config)
            assertEquals(world.time, info.time, 0.001f)
            assertEquals(1, SaveGame.load(file).ais.size)
            assertEquals(null, SaveGame.info(File(dir, "missing.bin")))
            file.writeText("garbage")
            assertEquals(null, SaveGame.info(file))
        } finally {
            dir.deleteRecursively()
        }
    }
}
