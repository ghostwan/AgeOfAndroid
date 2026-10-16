package com.ghostwan.ageofandroid.game

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A player order. Every action from the HUD goes through a command so that, in multiplayer, guests
 * can send it to the host, which applies it to the authoritative [World].
 */
sealed class Command {
    abstract val player: Int

    /** Contextual order (move, gather, build, attack...) at (x, y), on [targetId] if any (-1 for none). */
    data class Smart(override val player: Int, val units: List<Int>, val x: Float, val y: Float, val targetId: Int) : Command()
    data class Stop(override val player: Int, val units: List<Int>) : Command()
    data class Place(override val player: Int, val type: BuildingType, val tx: Int, val ty: Int, val builders: List<Int>) : Command()
    data class Train(override val player: Int, val building: Int, val unit: UnitType) : Command()
    data class Research(override val player: Int, val building: Int, val tech: Tech) : Command()
    data class AgeUp(override val player: Int, val building: Int) : Command()
    data class CancelQueue(override val player: Int, val building: Int, val index: Int) : Command()
    data class Rally(override val player: Int, val building: Int, val x: Float, val y: Float) : Command()
    data class Delete(override val player: Int, val ids: List<Int>) : Command()

    fun write(d: DataOutputStream) {
        d.writeByte(kind())
        d.writeByte(player)
        when (this) {
            is Smart -> {
                writeIds(d, units)
                d.writeFloat(x)
                d.writeFloat(y)
                d.writeInt(targetId)
            }
            is Stop -> writeIds(d, units)
            is Place -> {
                d.writeByte(type.ordinal)
                d.writeShort(tx)
                d.writeShort(ty)
                writeIds(d, builders)
            }
            is Train -> {
                d.writeInt(building)
                d.writeByte(unit.ordinal)
            }
            is Research -> {
                d.writeInt(building)
                d.writeByte(tech.ordinal)
            }
            is AgeUp -> d.writeInt(building)
            is CancelQueue -> {
                d.writeInt(building)
                d.writeByte(index)
            }
            is Rally -> {
                d.writeInt(building)
                d.writeFloat(x)
                d.writeFloat(y)
            }
            is Delete -> writeIds(d, ids)
        }
    }

    private fun kind() = when (this) {
        is Smart -> 0
        is Stop -> 1
        is Place -> 2
        is Train -> 3
        is Research -> 4
        is AgeUp -> 5
        is CancelQueue -> 6
        is Rally -> 7
        is Delete -> 8
    }

    companion object {
        fun read(d: DataInputStream): Command {
            val kind = d.readByte().toInt()
            val player = d.readByte().toInt()
            return when (kind) {
                0 -> Smart(player, readIds(d), d.readFloat(), d.readFloat(), d.readInt())
                1 -> Stop(player, readIds(d))
                2 -> Place(player, BuildingType.entries[d.readByte().toInt()], d.readShort().toInt(), d.readShort().toInt(), readIds(d))
                3 -> Train(player, d.readInt(), UnitType.entries[d.readByte().toInt()])
                4 -> Research(player, d.readInt(), Tech.entries[d.readByte().toInt()])
                5 -> AgeUp(player, d.readInt())
                6 -> CancelQueue(player, d.readInt(), d.readByte().toInt())
                7 -> Rally(player, d.readInt(), d.readFloat(), d.readFloat())
                8 -> Delete(player, readIds(d))
                else -> throw IllegalArgumentException("Unknown command $kind")
            }
        }

        private fun writeIds(d: DataOutputStream, ids: List<Int>) {
            d.writeShort(ids.size)
            ids.forEach { d.writeInt(it) }
        }

        private fun readIds(d: DataInputStream): List<Int> = List(d.readShort().toInt()) { d.readInt() }
    }
}

/**
 * Applies [cmd] to the world for [asPlayer], ignoring entities that don't belong to them (a guest
 * can't order someone else's units). Returns an error message for the player, or null.
 */
fun World.apply(cmd: Command, asPlayer: Int = cmd.player): String? {
    // The host passes the seat of the guest who sent the command: never trust the id inside it.
    val me = asPlayer
    fun ownUnits(ids: List<Int>) = ids.mapNotNull { entity(it) as? GameUnit }.filter { it.owner == me }
    fun ownBuilding(id: Int) = (entity(id) as? Building)?.takeIf { it.owner == me }
    fun result(r: World.QueueResult) = (r as? World.QueueResult.Error)?.reason
    return when (cmd) {
        is Command.Smart -> {
            commandSmart(ownUnits(cmd.units), cmd.x, cmd.y, if (cmd.targetId >= 0) entity(cmd.targetId) else null)
            null
        }
        is Command.Stop -> {
            commandStop(ownUnits(cmd.units))
            null
        }
        is Command.Place -> {
            val builders = ownUnits(cmd.builders).filter { it.type == UnitType.VILLAGER }
            if (placeBuilding(me, cmd.type, cmd.tx, cmd.ty, builders) != null) {
                null
            } else if (!players[me].res.canAfford(players[me].buildingCost(cmd.type))) {
                "Ressources insuffisantes"
            } else {
                "Emplacement invalide"
            }
        }
        is Command.Train -> ownBuilding(cmd.building)?.let { result(train(it, cmd.unit)) }
        is Command.Research -> ownBuilding(cmd.building)?.let { result(research(it, cmd.tech)) }
        is Command.AgeUp -> ownBuilding(cmd.building)?.let { result(advanceAge(it)) }
        is Command.CancelQueue -> {
            ownBuilding(cmd.building)?.let { cancelQueue(it, cmd.index) }
            null
        }
        is Command.Rally -> {
            ownBuilding(cmd.building)?.let { setRally(it, cmd.x, cmd.y) }
            null
        }
        is Command.Delete -> {
            cmd.ids.mapNotNull { entity(it) }.filter { it.owner == me }.forEach { deleteEntity(it) }
            null
        }
    }
}
