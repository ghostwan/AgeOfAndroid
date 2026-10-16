package com.ghostwan.ageofandroid.game

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Summary shown on the main menu without loading the whole game. */
data class SaveInfo(val config: GameConfig, val time: Float, val age: Age, val savedAt: Long)

/**
 * Versioned binary save of a [World]. Paths, projectiles in flight and transient timers are not
 * stored: units recompute their paths on load, which is invisible to the player.
 */
object SaveGame {
    private const val MAGIC = 0x414F4153 // "AOAS"
    private const val VERSION = 2 // 2: player teams and viewer

    fun save(world: World, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().buffered().use { write(world, it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Cannot write ${file.path}")
        }
    }

    fun load(file: File): World = file.inputStream().buffered().use { read(it) }

    /** Reads only the header; returns null when the file is missing or unreadable. */
    fun info(file: File): SaveInfo? = try {
        if (!file.exists()) null else file.inputStream().buffered().use { input ->
            val d = DataInputStream(input)
            readHeader(d)
            val config = readConfig(d)
            val time = d.readFloat()
            val age = Age.entries[d.readInt()]
            SaveInfo(config, time, age, file.lastModified())
        }
    } catch (e: Exception) {
        null
    }

    fun write(world: World, output: OutputStream) {
        val d = DataOutputStream(output)
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        val c = world.config
        d.writeInt(c.civ.ordinal)
        d.writeInt(c.opponents)
        d.writeInt(c.difficulty.ordinal)
        d.writeInt(c.mapSize)
        d.writeLong(c.seed)
        d.writeFloat(world.time)
        d.writeInt(world.human.age.ordinal)

        d.writeInt(world.nextId)
        d.writeBoolean(world.revealAll)
        d.writeInt(world.viewer)

        // Map
        val map = world.map
        for (i in map.terrain.indices) {
            d.writeByte(map.terrain[i].ordinal)
            d.writeByte(map.shade[i].toInt())
            d.writeBoolean(world.explored[i])
        }

        // Players
        d.writeInt(world.players.size)
        for (p in world.players) {
            d.writeUTF(p.name)
            d.writeLong(p.color)
            d.writeInt(p.civ.ordinal)
            d.writeBoolean(p.isHuman)
            d.writeInt(p.startX)
            d.writeInt(p.startY)
            d.writeInt(p.team)
            ResourceType.entries.forEach { d.writeFloat(p.res.exact(it)) }
            d.writeInt(p.age.ordinal)
            d.writeInt(p.techs.size)
            p.techs.forEach { d.writeInt(it.ordinal) }
            d.writeInt(p.researching.size)
            p.researching.forEach { d.writeInt(it.ordinal) }
            d.writeBoolean(p.researchingAge)
            d.writeBoolean(p.defeated)
            d.writeInt(p.unitsKilled)
            d.writeInt(p.unitsLost)
            d.writeInt(p.buildingsRazed)
            p.gathered.forEach { d.writeFloat(it) }
        }

        // AIs
        d.writeInt(world.ais.size)
        for (ai in world.ais) {
            d.writeInt(ai.player.id)
            d.writeFloat(ai.nextAttackTime)
            d.writeInt(ai.attackWaveSize)
        }

        // Resource nodes
        val nodes = world.nodes.filter { it.alive }
        d.writeInt(nodes.size)
        for (n in nodes) {
            d.writeInt(n.id)
            d.writeInt(n.kind.ordinal)
            d.writeInt(n.tx)
            d.writeInt(n.ty)
            d.writeFloat(n.amount)
        }

        // Buildings
        val buildings = world.buildings.filter { it.alive }
        d.writeInt(buildings.size)
        for (b in buildings) {
            d.writeInt(b.id)
            d.writeInt(b.owner)
            d.writeInt(b.type.ordinal)
            d.writeInt(b.tx)
            d.writeInt(b.ty)
            d.writeFloat(b.buildMaxHp)
            d.writeFloat(b.hp)
            d.writeFloat(b.progress)
            d.writeFloat(b.queueProgress)
            d.writeFloat(b.rallyX)
            d.writeFloat(b.rallyY)
            d.writeFloat(b.food)
            d.writeFloat(b.wonderTimer)
            d.writeInt(b.queue.size)
            for (q in b.queue) {
                when {
                    q.unit != null -> {
                        d.writeByte(0)
                        d.writeInt(q.unit.ordinal)
                    }
                    q.tech != null -> {
                        d.writeByte(1)
                        d.writeInt(q.tech.ordinal)
                    }
                    else -> {
                        d.writeByte(2)
                        d.writeInt(q.age!!.ordinal)
                    }
                }
            }
        }

        // Units
        val units = world.units.filter { it.alive }
        d.writeInt(units.size)
        for (u in units) {
            d.writeInt(u.id)
            d.writeInt(u.owner)
            d.writeInt(u.type.ordinal)
            d.writeFloat(u.x)
            d.writeFloat(u.y)
            d.writeFloat(u.maxHp)
            d.writeFloat(u.hp)
            d.writeInt(u.task.ordinal)
            d.writeInt(u.targetId)
            d.writeFloat(u.destX)
            d.writeFloat(u.destY)
            d.writeInt(u.carryType?.ordinal ?: -1)
            d.writeFloat(u.carryAmount)
            d.writeInt(u.lastGatherKind?.ordinal ?: -1)
            d.writeBoolean(u.lastGatherFarm)
            d.writeFloat(u.lastGatherX)
            d.writeFloat(u.lastGatherY)
            d.writeBoolean(u.facingLeft)
        }
        d.flush()
    }

    fun read(input: InputStream): World {
        val d = DataInputStream(input)
        val version = readHeader(d)
        val config = readConfig(d)
        val world = World(config, generate = false)
        world.time = d.readFloat()
        d.readInt() // human age, header only
        world.nextId = d.readInt()
        world.revealAll = d.readBoolean()
        if (version >= 2) world.viewer = d.readInt()

        val map = world.map
        for (i in map.terrain.indices) {
            map.terrain[i] = Terrain.entries[d.readByte().toInt()]
            map.shade[i] = d.readByte()
            world.explored[i] = d.readBoolean()
        }

        repeat(d.readInt()) { id ->
            val name = d.readUTF()
            val color = d.readLong()
            val civ = Civ.entries[d.readInt()]
            val human = d.readBoolean()
            val sx = d.readInt()
            val sy = d.readInt()
            val team = if (version >= 2) d.readInt() else id
            val p = Player(id, name, color, civ, human, sx, sy, team)
            ResourceType.entries.forEach { p.res.setExact(it, d.readFloat()) }
            p.age = Age.entries[d.readInt()]
            repeat(d.readInt()) { p.techs += Tech.entries[d.readInt()] }
            repeat(d.readInt()) { p.researching += Tech.entries[d.readInt()] }
            p.researchingAge = d.readBoolean()
            p.defeated = d.readBoolean()
            p.unitsKilled = d.readInt()
            p.unitsLost = d.readInt()
            p.buildingsRazed = d.readInt()
            for (i in p.gathered.indices) p.gathered[i] = d.readFloat()
            world.addPlayer(p)
        }

        val aiState = HashMap<Int, Pair<Float, Int>>()
        repeat(d.readInt()) { aiState[d.readInt()] = Pair(d.readFloat(), d.readInt()) }

        repeat(d.readInt()) {
            val id = d.readInt()
            val n = ResourceNode(id, NodeKind.entries[d.readInt()], d.readInt(), d.readInt())
            n.amount = d.readFloat()
            world.restore(n)
        }

        repeat(d.readInt()) {
            val id = d.readInt()
            val owner = d.readInt()
            val type = BuildingType.entries[d.readInt()]
            val b = Building(id, owner, type, d.readInt(), d.readInt(), d.readFloat())
            b.hp = d.readFloat()
            b.progress = d.readFloat()
            b.queueProgress = d.readFloat()
            b.rallyX = d.readFloat()
            b.rallyY = d.readFloat()
            b.food = d.readFloat()
            b.wonderTimer = d.readFloat()
            repeat(d.readInt()) {
                b.queue += when (d.readByte().toInt()) {
                    0 -> QueueItem(unit = UnitType.entries[d.readInt()])
                    1 -> QueueItem(tech = Tech.entries[d.readInt()])
                    else -> QueueItem(age = Age.entries[d.readInt()])
                }
            }
            world.restore(b)
        }

        val moving = ArrayList<GameUnit>()
        repeat(d.readInt()) {
            val id = d.readInt()
            val owner = d.readInt()
            val type = UnitType.entries[d.readInt()]
            val x = d.readFloat()
            val y = d.readFloat()
            val u = GameUnit(id, owner, type, x, y, d.readFloat())
            u.hp = d.readFloat()
            u.task = Task.entries[d.readInt()]
            u.targetId = d.readInt()
            u.destX = d.readFloat()
            u.destY = d.readFloat()
            u.carryType = d.readInt().takeIf { it >= 0 }?.let { ResourceType.entries[it] }
            u.carryAmount = d.readFloat()
            u.lastGatherKind = d.readInt().takeIf { it >= 0 }?.let { NodeKind.entries[it] }
            u.lastGatherFarm = d.readBoolean()
            u.lastGatherX = d.readFloat()
            u.lastGatherY = d.readFloat()
            u.facingLeft = d.readBoolean()
            world.restore(u)
            if (u.task == Task.MOVE) moving += u
        }

        world.finishSetup()
        world.ais.forEach { ai ->
            aiState[ai.player.id]?.let { (next, wave) ->
                ai.nextAttackTime = next
                ai.attackWaveSize = wave
            }
        }
        // Units that were walking somewhere need their path again.
        moving.forEach { world.setDestinationPoint(it, it.destX, it.destY) }
        return world
    }

    private fun readHeader(d: DataInputStream): Int {
        if (d.readInt() != MAGIC) throw IOException("Not a save file")
        val version = d.readInt()
        if (version !in 1..VERSION) throw IOException("Unsupported save version $version")
        return version
    }

    private fun readConfig(d: DataInputStream) = GameConfig(
        civ = Civ.entries[d.readInt()],
        opponents = d.readInt(),
        difficulty = Difficulty.entries[d.readInt()],
        mapSize = d.readInt(),
        seed = d.readLong(),
    )
}
