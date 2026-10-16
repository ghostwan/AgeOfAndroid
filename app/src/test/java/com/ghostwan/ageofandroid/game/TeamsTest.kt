package com.ghostwan.ageofandroid.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamsTest {
    private fun coop() = World(
        GameConfig(
            mapSize = 56, seed = 11L,
            slots = listOf(
                PlayerSlot("Alice", Civ.FRANKS, human = true, team = 0),
                PlayerSlot("Bob", Civ.BRITONS, human = true, team = 0),
                PlayerSlot("IA", Civ.TEUTONS, human = false, team = 1),
            ),
        ),
    )

    @Test
    fun slotsCreateHumansAndAis() {
        val w = coop()
        assertEquals(3, w.players.size)
        assertEquals(listOf("Alice", "Bob", "IA"), w.players.map { it.name })
        assertTrue(w.players[1].isHuman)
        assertEquals(Civ.BRITONS, w.players[1].civ)
        // Only the AI seat gets an AI.
        assertEquals(listOf(2), w.ais.map { it.player.id })
    }

    @Test
    fun alliesStartOnNeighbouringCorners() {
        repeat(5) { seed ->
            val w = World(
                GameConfig(
                    mapSize = 56, seed = seed.toLong(),
                    slots = listOf(
                        PlayerSlot("A", Civ.FRANKS, true, 0),
                        PlayerSlot("IA", Civ.FRANKS, false, 1),
                        PlayerSlot("B", Civ.FRANKS, true, 0),
                    ),
                ),
            )
            val a = w.players[0]
            val b = w.players[2]
            // Same side of the map: they share one coordinate.
            assertTrue(a.startX == b.startX || a.startY == b.startY)
        }
    }

    @Test
    fun alliesAreNotEnemies() {
        val w = coop()
        assertFalse(w.isEnemy(0, 1))
        assertTrue(w.isAlly(0, 1))
        assertTrue(w.isEnemy(0, 2))
        assertTrue(w.isEnemy(1, 2))
        // Allied units are visible to each other (shared vision).
        val bobUnit = w.units.first { it.owner == 1 }
        assertTrue(w.canSee(bobUnit))
    }

    @Test
    fun teamWinsWhenOtherTeamsAreDefeated() {
        val w = coop()
        // Remove everything the AI owns.
        w.units.filter { it.owner == 2 }.forEach { w.deleteEntity(it) }
        w.buildings.filter { it.owner == 2 }.forEach { w.deleteEntity(it) }
        w.forceAdvance(2f)
        assertEquals(0, w.winner)
        assertTrue(w.hasWon(0))
        assertTrue(w.hasWon(1))
    }

    @Test
    fun gameGoesOnWhileAHumanTeamRemains() {
        val w = World(
            GameConfig(
                mapSize = 56, seed = 12L,
                slots = listOf(
                    PlayerSlot("A", Civ.FRANKS, true, 0),
                    PlayerSlot("B", Civ.FRANKS, true, 1),
                    PlayerSlot("C", Civ.FRANKS, true, 2),
                ),
            ),
        )
        w.units.filter { it.owner == 2 }.forEach { w.deleteEntity(it) }
        w.buildings.filter { it.owner == 2 }.forEach { w.deleteEntity(it) }
        w.forceAdvance(2f)
        assertTrue(w.players[2].defeated)
        assertNull(w.winner)
    }

    @Test
    fun eventsAreAddressed() {
        val w = coop()
        w.pushEvent("pour Bob", to = 1)
        w.pushEvent("pas pour Alice", except = 0)
        val alice = w.eventsFor(0, 0).map { it.text }
        assertFalse("pour Bob" in alice)
        assertFalse("pas pour Alice" in alice)
        assertEquals(listOf("pour Bob", "pas pour Alice"), w.eventsFor(1, 0).map { it.text }.takeLast(2))
    }
}
