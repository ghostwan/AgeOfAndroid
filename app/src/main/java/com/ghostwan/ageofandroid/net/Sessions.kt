package com.ghostwan.ageofandroid.net

import com.ghostwan.ageofandroid.game.Civ
import com.ghostwan.ageofandroid.game.Command
import com.ghostwan.ageofandroid.game.Difficulty
import com.ghostwan.ageofandroid.game.GameConfig
import com.ghostwan.ageofandroid.game.SaveGame
import com.ghostwan.ageofandroid.game.SnapshotReader
import com.ghostwan.ageofandroid.game.SnapshotWriter
import com.ghostwan.ageofandroid.game.World
import com.ghostwan.ageofandroid.game.apply
import java.io.IOException

/** Called by the game loop around each simulation tick. */
interface NetHook {
    fun beforeTick(dt: Float)
    fun afterTick(dt: Float)
}

/**
 * Host side of a multiplayer game: runs the lobby, then the authoritative [World]. Guests send
 * commands; the host applies them and sends snapshots [SNAPSHOT_RATE] times per second.
 * Everything here runs on the caller's thread through [poll] / the [NetHook] methods.
 */
class HostSession(hostName: String, hostCiv: Civ, port: Int = DEFAULT_PORT) : NetHook {
    private class Guest(val clientId: Int, val link: Link) {
        var joined = false
        var playerId = -1
        var lastEventSeq = 0L
    }

    private val listener = TcpListener(port)
    val port get() = listener.port
    private val guests = ArrayList<Guest>()
    private var nextClientId = 1

    var lobby = Lobby(hostName, seats = listOf(Seat(hostName, hostCiv, SeatKind.HOST, 0)))
        private set

    /** Bumped on every lobby change, for the UI. */
    var version = 0
        private set

    var world: World? = null
        private set

    private val snapshots = SnapshotWriter()
    private var snapshotTimer = 0f

    // ---------------------------------------------------------------- Lobby (host controls)

    fun setMode(mode: GameMode) = updateLobby(lobby.copy(mode = mode))
    fun setMapSize(size: Int) = updateLobby(lobby.copy(mapSize = size))
    fun setDifficulty(d: Difficulty) = updateLobby(lobby.copy(difficulty = d))
    fun setHostCiv(civ: Civ) = updateLobby(lobby.copy(seats = lobby.seats.map { if (it.kind == SeatKind.HOST) it.copy(civ = civ) else it }))

    fun addAi() {
        if (lobby.seats.size >= MAX_SEATS) return
        val civ = Civ.entries.random()
        val n = lobby.ais + 1
        updateLobby(lobby.copy(seats = lobby.seats + Seat("IA $n (${civ.label})", civ, SeatKind.AI, -1)))
    }

    /** Removes an AI, or sends a guest away. */
    fun removeSeat(index: Int) {
        val seat = lobby.seats.getOrNull(index) ?: return
        if (seat.kind == SeatKind.HOST) return
        if (seat.kind == SeatKind.GUEST) {
            guests.firstOrNull { it.clientId == seat.clientId }?.let { reject(it, "L'hôte vous a retiré de la partie.") }
        }
        updateLobby(lobby.copy(seats = lobby.seats.filterIndexed { i, _ -> i != index }))
    }

    private fun updateLobby(l: Lobby) {
        lobby = l
        version++
        val msg = message(Msg.LOBBY) { l.write(this) }
        guests.filter { it.joined }.forEach { it.link.send(msg) }
    }

    /** Creates the world and sends it to everyone. */
    fun start(seed: Long = System.currentTimeMillis()): World {
        val host = lobby.seats.first { it.kind == SeatKind.HOST }
        val config = GameConfig(
            civ = host.civ, opponents = lobby.seats.size - 1, difficulty = lobby.difficulty,
            mapSize = lobby.mapSize, seed = seed, slots = lobby.toSlots(),
        )
        val w = World(config)
        w.viewer = lobby.seats.indexOf(host)
        world = w
        // Late joiners are refused from now on.
        listener.close()
        val save = deflate { SaveGame.write(w, this) }
        for (g in guests) {
            g.playerId = lobby.seats.indexOfFirst { it.kind == SeatKind.GUEST && it.clientId == g.clientId }
            if (g.playerId < 0) continue
            g.link.send(
                message(Msg.START) {
                    writeByte(g.playerId)
                    writeBlob(save)
                },
            )
        }
        version++
        return w
    }

    // ---------------------------------------------------------------- Network pump

    fun poll() {
        while (true) {
            val link = listener.poll() ?: break
            guests += Guest(nextClientId++, link)
        }
        for (g in guests.toList()) {
            while (true) {
                val msg = g.link.poll() ?: break
                handle(g, msg)
            }
            if (!g.link.isOpen) disconnected(g)
        }
    }

    private fun handle(g: Guest, msg: ByteArray) {
        val d = payload(msg)
        when (msg.type()) {
            Msg.HELLO -> {
                val protocol = d.readInt()
                val name = d.readUTF().take(24).ifBlank { "Joueur ${g.clientId}" }
                val civ = Civ.entries.getOrElse(d.readByte().toInt()) { Civ.FRANKS }
                when {
                    protocol != PROTOCOL_VERSION -> reject(g, "Versions différentes : mettez les deux téléphones à jour.")
                    world != null -> reject(g, "La partie a déjà commencé.")
                    lobby.seats.size >= MAX_SEATS -> reject(g, "La partie est complète.")
                    else -> {
                        g.joined = true
                        g.link.send(message(Msg.WELCOME) { writeInt(g.clientId) })
                        updateLobby(lobby.copy(seats = lobby.seats + Seat(name, civ, SeatKind.GUEST, g.clientId)))
                    }
                }
            }
            Msg.SET_CIV -> if (world == null) {
                val civ = Civ.entries.getOrElse(d.readByte().toInt()) { Civ.FRANKS }
                updateLobby(lobby.copy(seats = lobby.seats.map { if (it.kind == SeatKind.GUEST && it.clientId == g.clientId) it.copy(civ = civ) else it }))
            }
            Msg.COMMAND -> {
                val w = world ?: return
                if (g.playerId < 0) return
                val cmd = try {
                    Command.read(d)
                } catch (e: Exception) {
                    return
                }
                w.apply(cmd, asPlayer = g.playerId)?.let { w.pushEvent(it, alert = true, to = g.playerId) }
            }
            Msg.BYE -> g.link.close()
        }
    }

    private fun reject(g: Guest, reason: String) {
        g.link.send(message(Msg.REJECT) { writeUTF(reason) })
        g.link.close()
        g.joined = false
    }

    private fun disconnected(g: Guest) {
        guests -= g
        val w = world
        if (w == null) {
            if (lobby.seats.any { it.kind == SeatKind.GUEST && it.clientId == g.clientId }) {
                updateLobby(lobby.copy(seats = lobby.seats.filterNot { it.kind == SeatKind.GUEST && it.clientId == g.clientId }))
            }
        } else if (g.playerId >= 0) {
            val p = w.players[g.playerId]
            if (!p.defeated) {
                w.handOverToAi(p)
                w.pushEvent("${p.name} a quitté la partie : une IA prend le relais.", alert = true)
            }
        }
    }

    // ---------------------------------------------------------------- Game loop

    override fun beforeTick(dt: Float) = poll()

    override fun afterTick(dt: Float) {
        val w = world ?: return
        snapshotTimer += dt
        if (snapshotTimer < 1f / SNAPSHOT_RATE) return
        snapshotTimer = 0f
        val players = guests.filter { it.playerId >= 0 && it.link.isOpen }
        if (players.isEmpty()) return
        val snapshot = message(Msg.SNAPSHOT) { writeBlob(deflate { snapshots.write(w, this) }) }
        for (g in players) {
            g.link.send(snapshot)
            val events = w.eventsFor(g.playerId, g.lastEventSeq)
            g.lastEventSeq = w.lastEventSeq
            if (events.isNotEmpty()) {
                g.link.send(
                    message(Msg.EVENTS) {
                        writeByte(events.size)
                        events.forEach {
                            writeUTF(it.text)
                            writeFloat(it.x)
                            writeFloat(it.y)
                            writeBoolean(it.alert)
                        }
                    },
                )
            }
        }
    }

    val connectedGuests get() = guests.count { it.joined && it.link.isOpen }

    fun close() {
        val bye = message(Msg.BYE)
        guests.forEach {
            it.link.send(bye)
            it.link.close()
        }
        guests.clear()
        listener.close()
    }

    companion object {
        const val SNAPSHOT_RATE = 10
    }
}

/** Guest side: joins a host's lobby, then mirrors the host's world and sends commands. */
class GuestSession(private val link: Link, val name: String, civ: Civ) : NetHook {
    enum class State { JOINING, LOBBY, PLAYING, CLOSED }

    var state = State.JOINING
        private set

    /** Why the session ended (shown to the player). */
    var closeReason: String? = null
        private set

    var clientId = -1
        private set
    var lobby: Lobby? = null
        private set
    var world: World? = null
        private set
    var playerId = -1
        private set

    /** Bumped on every change, for the UI. */
    var version = 0
        private set

    private var reader: SnapshotReader? = null
    private var fogTimer = 0f

    init {
        link.send(
            message(Msg.HELLO) {
                writeInt(PROTOCOL_VERSION)
                writeUTF(name)
                writeByte(civ.ordinal)
            },
        )
    }

    fun setCiv(civ: Civ) = link.send(message(Msg.SET_CIV) { writeByte(civ.ordinal) })

    fun send(cmd: Command) = link.send(message(Msg.COMMAND) { cmd.write(this) })

    fun poll() {
        while (state != State.CLOSED) {
            val msg = link.poll() ?: break
            try {
                handle(msg)
            } catch (e: IOException) {
                end("Données invalides reçues de l'hôte.")
            }
        }
        if (state != State.CLOSED && !link.isOpen) end("Connexion perdue avec l'hôte.")
    }

    private fun handle(msg: ByteArray) {
        val d = payload(msg)
        when (msg.type()) {
            Msg.WELCOME -> {
                clientId = d.readInt()
                state = State.LOBBY
            }
            Msg.LOBBY -> lobby = Lobby.read(d)
            Msg.START -> {
                playerId = d.readByte().toInt()
                val w = SaveGame.read(inflate(d.readBlob()))
                w.viewer = playerId
                java.util.Arrays.fill(w.explored, false)
                w.updateFog()
                reader = SnapshotReader(w)
                world = w
                state = State.PLAYING
            }
            Msg.SNAPSHOT -> reader?.apply(inflate(d.readBlob()))
            Msg.EVENTS -> {
                val w = world ?: return
                repeat(d.readByte().toInt()) {
                    w.pushEvent(d.readUTF(), d.readFloat(), d.readFloat(), d.readBoolean(), to = playerId)
                }
            }
            Msg.REJECT -> end(d.readUTF())
            Msg.BYE -> end(if (state == State.PLAYING) "L'hôte a quitté la partie." else "L'hôte a fermé la partie.")
        }
        version++
    }

    private fun end(reason: String) {
        if (state == State.CLOSED) return
        closeReason = reason
        state = State.CLOSED
        link.close()
        version++
    }

    override fun beforeTick(dt: Float) = poll()

    override fun afterTick(dt: Float) {
        reader?.interpolate(dt)
        fogTimer -= dt
        if (fogTimer <= 0f) {
            fogTimer = 0.2f
            world?.updateFog()
        }
    }

    fun close() {
        if (state == State.CLOSED) return
        link.send(message(Msg.BYE))
        link.close()
        state = State.CLOSED
    }
}
