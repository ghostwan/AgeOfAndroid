package com.ghostwan.ageofandroid.net

import com.ghostwan.ageofandroid.game.Civ
import com.ghostwan.ageofandroid.game.Command
import com.ghostwan.ageofandroid.game.GameUnit
import com.ghostwan.ageofandroid.game.UnitType
import com.ghostwan.ageofandroid.game.World
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host and guests talking over real TCP sockets on localhost. */
class LoopbackTest {
    private val host = HostSession("Hôte", Civ.FRANKS, port = 0)
    private val guests = ArrayList<GuestSession>()

    @After
    fun tearDown() {
        guests.forEach { it.close() }
        host.close()
    }

    private fun join(name: String, civ: Civ) =
        GuestSession(TcpLink.connect("127.0.0.1", host.port), name, civ).also { guests += it }

    /** Pumps everyone until [done] or timeout. */
    private fun pumpUntil(timeoutMs: Long = 5000, done: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            check(System.currentTimeMillis() < end) { "timeout" }
            host.poll()
            guests.forEach { it.poll() }
            Thread.sleep(5)
        }
    }

    /** Runs the host game loop and the guests' frames for [seconds] of game time. */
    private fun play(seconds: Float, world: World) {
        val dt = 0.05f
        var t = 0f
        while (t < seconds) {
            host.beforeTick(dt)
            world.update(dt)
            host.afterTick(dt)
            Thread.sleep(2)
            guests.forEach {
                it.beforeTick(dt)
                it.afterTick(dt)
            }
            t += dt
        }
        // Let the last snapshot arrive.
        Thread.sleep(100)
        guests.forEach { it.poll() }
    }

    @Test
    fun lobbyThenGameStaysInSync() {
        val alice = join("Alice", Civ.BRITONS)
        val bob = join("Bob", Civ.TEUTONS)
        pumpUntil { host.lobby.seats.size == 3 && guests.all { it.lobby?.seats?.size == 3 } }
        assertEquals(GuestSession.State.LOBBY, alice.state)
        assertEquals(listOf("Hôte", "Alice", "Bob"), alice.lobby!!.seats.map { it.name })

        // Guests can change civ, the host the mode; everyone sees it.
        bob.setCiv(Civ.JAPANESE)
        host.setMode(GameMode.FFA)
        pumpUntil { alice.lobby?.seats?.get(2)?.civ == Civ.JAPANESE }
        assertEquals(null, host.lobby.blocker)

        val world = host.start(seed = 42L)
        pumpUntil { guests.all { it.state == GuestSession.State.PLAYING } }
        assertEquals(1, alice.playerId)
        assertEquals(2, bob.playerId)
        val aliceWorld = alice.world!!
        assertEquals(world.units.size, aliceWorld.units.size)
        assertEquals(Civ.JAPANESE, aliceWorld.players[2].civ)
        // Free-for-all: everyone is an enemy.
        assertTrue(aliceWorld.isEnemy(1, 2))
        assertTrue(aliceWorld.isEnemy(0, 1))

        // Alice orders her villagers somewhere: the host applies it, Alice sees them move.
        val villagers = aliceWorld.units.filter { it.owner == 1 && it.type == UnitType.VILLAGER }
        val start = villagers.first().let { Pair(it.x, it.y) }
        val p = aliceWorld.players[1]
        alice.send(Command.Smart(1, villagers.map { it.id }, p.startX + 4.5f, p.startY + 4.5f, -1))
        play(4f, world)
        val hostUnit = world.entity(villagers.first().id) as GameUnit
        assertTrue("host moved the unit", hostUnit.x != start.first || hostUnit.y != start.second)
        val mirrored = aliceWorld.entity(hostUnit.id) as GameUnit
        assertEquals(hostUnit.x, mirrored.x, 0.6f)
        assertEquals(hostUnit.y, mirrored.y, 0.6f)

        // A guest can't order someone else's units.
        val hostVillager = world.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        val before = Pair(hostVillager.x, hostVillager.y)
        bob.send(Command.Smart(0, listOf(hostVillager.id), 1f, 1f, -1))
        play(1f, world)
        assertEquals(before.first, hostVillager.x, 0.3f)

        // Training: resources and production queue show up on the guest.
        val tc = world.buildings.first { it.owner == 1 }
        alice.send(Command.Train(1, tc.id, UnitType.VILLAGER))
        play(0.5f, world)
        assertEquals(1, tc.queue.size)
        assertEquals(world.players[1].res[com.ghostwan.ageofandroid.game.ResourceType.FOOD], aliceWorld.players[1].res[com.ghostwan.ageofandroid.game.ResourceType.FOOD])
        assertEquals(1, (aliceWorld.entity(tc.id) as com.ghostwan.ageofandroid.game.Building).queue.size)

        // Bob leaves: an AI takes over his seat, Alice is told.
        bob.close()
        guests.remove(bob)
        play(1f, world)
        assertFalse(world.players[2].isHuman)
        assertTrue(world.ais.any { it.player.id == 2 })
        assertTrue(aliceWorld.events.any { it.text.contains("Bob a quitté") })
    }

    @Test
    fun coopNeedsAnAiAndPutsHumansTogether() {
        val alice = join("Alice", Civ.FRANKS)
        pumpUntil { host.lobby.seats.size == 2 }
        host.setMode(GameMode.COOP)
        assertNotNull(host.lobby.blocker)
        host.addAi()
        assertEquals(null, host.lobby.blocker)
        val world = host.start(seed = 7L)
        pumpUntil { alice.state == GuestSession.State.PLAYING }
        assertTrue(world.isAlly(0, 1))
        assertTrue(world.isEnemy(0, 2))
        assertTrue(alice.world!!.isAlly(1, 0))
        assertEquals(listOf(2), world.ais.map { it.player.id })
    }

    @Test
    fun lateJoinerAndHostLeaving() {
        val alice = join("Alice", Civ.FRANKS)
        pumpUntil { host.lobby.seats.size == 2 }
        host.start(seed = 1L)
        pumpUntil { alice.state == GuestSession.State.PLAYING }
        host.close()
        pumpUntil { alice.state == GuestSession.State.CLOSED }
        assertEquals("L'hôte a quitté la partie.", alice.closeReason)
    }
}
