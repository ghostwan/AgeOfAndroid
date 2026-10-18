package com.ghostwan.ageofandroid.net

import com.ghostwan.ageofandroid.game.Civ
import com.ghostwan.ageofandroid.game.Difficulty
import com.ghostwan.ageofandroid.game.MapType
import com.ghostwan.ageofandroid.game.PlayerSlot
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Message types. Every message is one byte of type followed by its payload, so the same bytes can
 * travel over TCP today or a WebSocket relay later.
 */
object Msg {
    /** Guest → host: protocol version, name, civ. */
    const val HELLO = 1

    /** Host → guest: the guest's client id. */
    const val WELCOME = 2

    /** Host → guests: the whole lobby. */
    const val LOBBY = 3

    /** Guest → host: new civilisation. */
    const val SET_CIV = 4

    /** Host → guest: the guest's player id and the initial world (compressed save). */
    const val START = 5

    /** Guest → host: one [com.ghostwan.ageofandroid.game.Command]. */
    const val COMMAND = 6

    /** Host → guests: compressed state snapshot. */
    const val SNAPSHOT = 7

    /** Host → guest: messages for that player. */
    const val EVENTS = 8

    /** Host → guest: refused (version, game full or started). */
    const val REJECT = 9

    /** Either way: leaving. */
    const val BYE = 10
}

/** Bump when messages or anything they encode (enums, snapshot layout) change. */
const val PROTOCOL_VERSION = 3

const val DEFAULT_PORT = 47820
const val MAX_SEATS = 4

enum class GameMode(val label: String, val description: String) {
    COOP("Coopération", "Tous les joueurs ensemble contre l'IA"),
    FFA("Chacun pour soi", "1 contre 1, 1 contre 1 contre 1… (IA facultatives)"),
}

enum class SeatKind { HOST, GUEST, AI }

/** [clientId] identifies a guest's connection (0 for the host, -1 for an AI). */
data class Seat(val name: String, val civ: Civ, val kind: SeatKind, val clientId: Int)

data class Lobby(
    val hostName: String,
    val mode: GameMode = GameMode.FFA,
    val mapSize: Int = 72,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val seats: List<Seat> = emptyList(),
    val mapType: MapType = MapType.ARABIA,
) {
    val humans get() = seats.count { it.kind != SeatKind.AI }
    val ais get() = seats.count { it.kind == SeatKind.AI }

    /** Why the game can't start yet, or null when it can. */
    val blocker: String?
        get() = when {
            humans < 2 -> "En attente d'un autre joueur…"
            mode == GameMode.COOP && ais == 0 -> "Ajoutez au moins une IA à affronter"
            else -> null
        }

    /** Team of each seat: coop puts every human on team 0 and the AIs on team 1. */
    fun teamOf(index: Int): Int = when (mode) {
        GameMode.COOP -> if (seats[index].kind == SeatKind.AI) 1 else 0
        GameMode.FFA -> index
    }

    fun toSlots(): List<PlayerSlot> = seats.mapIndexed { i, s -> PlayerSlot(s.name, s.civ, s.kind != SeatKind.AI, teamOf(i)) }

    fun write(d: DataOutputStream) {
        d.writeUTF(hostName)
        d.writeByte(mode.ordinal)
        d.writeShort(mapSize)
        d.writeByte(difficulty.ordinal)
        d.writeByte(mapType.ordinal)
        d.writeByte(seats.size)
        seats.forEach {
            d.writeUTF(it.name)
            d.writeByte(it.civ.ordinal)
            d.writeByte(it.kind.ordinal)
            d.writeInt(it.clientId)
        }
    }

    companion object {
        fun read(d: DataInputStream) = Lobby(
            hostName = d.readUTF(),
            mode = GameMode.entries[d.readByte().toInt()],
            mapSize = d.readShort().toInt(),
            difficulty = Difficulty.entries[d.readByte().toInt()],
            mapType = MapType.entries[d.readByte().toInt()],
            seats = List(d.readByte().toInt()) {
                Seat(d.readUTF(), Civ.entries[d.readByte().toInt()], SeatKind.entries[d.readByte().toInt()], d.readInt())
            },
        )
    }
}

/** Builds a message: type byte then whatever [body] writes. */
fun message(type: Int, body: DataOutputStream.() -> Unit = {}): ByteArray {
    val bytes = ByteArrayOutputStream()
    val d = DataOutputStream(bytes)
    d.writeByte(type)
    d.body()
    d.flush()
    return bytes.toByteArray()
}

/** Reader positioned after the type byte. */
fun payload(msg: ByteArray) = DataInputStream(ByteArrayInputStream(msg, 1, msg.size - 1))

fun ByteArray.type() = this[0].toInt()

fun deflate(body: DataOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    val d = DataOutputStream(DeflaterOutputStream(bytes, Deflater(Deflater.BEST_SPEED)))
    d.body()
    d.close()
    return bytes.toByteArray()
}

fun inflate(data: ByteArray) = DataInputStream(InflaterInputStream(ByteArrayInputStream(data)))

fun DataOutputStream.writeBlob(b: ByteArray) {
    writeInt(b.size)
    write(b)
}

fun DataInputStream.readBlob(): ByteArray = ByteArray(readInt()).also { readFully(it) }
