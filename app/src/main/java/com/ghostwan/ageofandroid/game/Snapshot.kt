package com.ghostwan.ageofandroid.game

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Multiplayer state sync. The host runs the real simulation and regularly sends a snapshot of
 * everything that changes (players, units, buildings, projectiles, and resource nodes that were
 * depleted or harvested); guests mirror it in their own [World], which they never simulate.
 * The map itself is static and sent once with the initial save.
 */
class SnapshotWriter {
    /** Node amounts as of the previous snapshot, to only send what changed. */
    private val lastNodes = HashMap<Int, Float>()

    fun write(world: World, d: DataOutputStream) {
        d.writeFloat(world.time)
        d.writeInt(world.winner ?: NO_WINNER)

        d.writeByte(world.players.size)
        for (p in world.players) {
            d.writeBoolean(p.isHuman)
            ResourceType.entries.forEach { d.writeFloat(p.res.exact(it)) }
            d.writeByte(p.age.ordinal)
            d.writeInt(techMask(p.techs))
            d.writeInt(techMask(p.researching))
            d.writeBoolean(p.researchingAge)
            d.writeBoolean(p.defeated)
            d.writeShort(p.pop)
            d.writeShort(p.popCap)
            d.writeShort(p.unitsKilled)
            d.writeShort(p.unitsLost)
            d.writeShort(p.buildingsRazed)
            p.gathered.forEach { d.writeFloat(it) }
        }

        val buildings = world.buildings.filter { it.alive }
        d.writeShort(buildings.size)
        for (b in buildings) {
            d.writeInt(b.id)
            d.writeByte(b.owner)
            d.writeByte(b.type.ordinal)
            d.writeShort(b.tx)
            d.writeShort(b.ty)
            d.writeFloat(b.buildMaxHp)
            d.writeFloat(b.hp)
            d.writeFloat(b.progress)
            d.writeFloat(b.queueProgress)
            d.writeFloat(b.rallyX)
            d.writeFloat(b.rallyY)
            d.writeFloat(b.food)
            d.writeFloat(b.wonderTimer)
            d.writeByte(b.queue.size)
            for (q in b.queue) {
                when {
                    q.unit != null -> {
                        d.writeByte(0)
                        d.writeByte(q.unit.ordinal)
                    }
                    q.tech != null -> {
                        d.writeByte(1)
                        d.writeByte(q.tech.ordinal)
                    }
                    else -> {
                        d.writeByte(2)
                        d.writeByte(q.age!!.ordinal)
                    }
                }
            }
        }

        val units = world.units.filter { it.alive }
        d.writeShort(units.size)
        for (u in units) {
            d.writeInt(u.id)
            d.writeByte(u.owner)
            d.writeByte(u.type.ordinal)
            d.writeFloat(u.x)
            d.writeFloat(u.y)
            d.writeFloat(u.maxHp)
            d.writeFloat(u.hp)
            d.writeByte(u.task.ordinal)
            d.writeInt(u.targetId)
            d.writeByte(u.carryType?.ordinal ?: -1)
            d.writeFloat(u.carryAmount)
            d.writeBoolean(u.facingLeft)
            d.writeBoolean(u.moving)
            d.writeFloat(u.animTime)
        }

        val projectiles = world.projectiles.filter { it.alive }
        d.writeShort(projectiles.size)
        for (p in projectiles) {
            d.writeFloat(p.x)
            d.writeFloat(p.y)
            d.writeInt(p.targetId)
            d.writeFloat(p.progress)
        }

        // Resource nodes: only removals and amount changes since the last snapshot.
        val alive = HashSet<Int>()
        val changed = ArrayList<ResourceNode>()
        for (n in world.nodes) {
            if (!n.alive) continue
            alive += n.id
            if (lastNodes[n.id] != n.amount) changed += n
        }
        val removed = lastNodes.keys.filter { it !in alive }
        removed.forEach { lastNodes.remove(it) }
        changed.forEach { lastNodes[it.id] = it.amount }
        d.writeShort(removed.size)
        removed.forEach { d.writeInt(it) }
        d.writeShort(changed.size)
        changed.forEach {
            d.writeInt(it.id)
            d.writeFloat(it.amount)
        }
    }

    companion object {
        const val NO_WINNER = Int.MIN_VALUE

        fun techMask(techs: Set<Tech>): Int = techs.fold(0) { acc, t -> acc or (1 shl t.ordinal) }
        fun techsOf(mask: Int): List<Tech> = Tech.entries.filter { mask and (1 shl it.ordinal) != 0 }
    }
}

/** Guest side: applies snapshots to a mirror world and smooths unit movement between them. */
class SnapshotReader(private val world: World) {
    private class Track(var fromX: Float, var fromY: Float, var toX: Float, var toY: Float)

    private val tracks = HashMap<Int, Track>()
    private var elapsed = 0f
    private var interval = 0.1f

    init {
        // The guest only mirrors the host: no local AI, nothing simulated.
        world.ais.clear()
    }

    fun apply(d: DataInputStream) {
        world.time = d.readFloat()
        world.winner = d.readInt().takeIf { it != SnapshotWriter.NO_WINNER }

        val playerCount = d.readByte().toInt()
        for (i in 0 until playerCount) {
            val p = world.players[i]
            p.isHuman = d.readBoolean()
            ResourceType.entries.forEach { p.res.setExact(it, d.readFloat()) }
            p.age = Age.entries[d.readByte().toInt()]
            p.techs.clear()
            p.techs += SnapshotWriter.techsOf(d.readInt())
            p.researching.clear()
            p.researching += SnapshotWriter.techsOf(d.readInt())
            p.researchingAge = d.readBoolean()
            p.defeated = d.readBoolean()
            p.pop = d.readShort().toInt()
            p.popCap = d.readShort().toInt()
            p.unitsKilled = d.readShort().toInt()
            p.unitsLost = d.readShort().toInt()
            p.buildingsRazed = d.readShort().toInt()
            for (k in p.gathered.indices) p.gathered[k] = d.readFloat()
        }

        // Buildings
        val seenBuildings = HashSet<Int>()
        repeat(d.readShort().toInt()) {
            val id = d.readInt()
            val owner = d.readByte().toInt()
            val type = BuildingType.entries[d.readByte().toInt()]
            val tx = d.readShort().toInt()
            val ty = d.readShort().toInt()
            val maxHp = d.readFloat()
            val b = (world.entity(id) as? Building) ?: Building(id, owner, type, tx, ty, maxHp).also { world.restore(it) }
            b.owner = owner
            b.buildMaxHp = maxHp
            b.hp = d.readFloat()
            b.progress = d.readFloat()
            b.queueProgress = d.readFloat()
            b.rallyX = d.readFloat()
            b.rallyY = d.readFloat()
            b.food = d.readFloat()
            b.wonderTimer = d.readFloat()
            b.queue.clear()
            repeat(d.readByte().toInt()) {
                b.queue += when (d.readByte().toInt()) {
                    0 -> QueueItem(unit = UnitType.entries[d.readByte().toInt()])
                    1 -> QueueItem(tech = Tech.entries[d.readByte().toInt()])
                    else -> QueueItem(age = Age.entries[d.readByte().toInt()])
                }
            }
            seenBuildings += id
        }
        world.buildings.filter { it.id !in seenBuildings }.forEach { world.forget(it) }
        world.buildings.removeAll { !it.alive }

        // Units
        val seenUnits = HashSet<Int>()
        repeat(d.readShort().toInt()) {
            val id = d.readInt()
            val owner = d.readByte().toInt()
            val type = UnitType.entries[d.readByte().toInt()]
            val x = d.readFloat()
            val y = d.readFloat()
            val maxHp = d.readFloat()
            var u = world.entity(id) as? GameUnit
            if (u == null) {
                u = GameUnit(id, owner, type, x, y, maxHp)
                world.restore(u)
                tracks[id] = Track(x, y, x, y)
            } else {
                // Restart the interpolation from where the unit is drawn now.
                val t = tracks.getOrPut(id) { Track(u.x, u.y, x, y) }
                t.fromX = u.x
                t.fromY = u.y
                t.toX = x
                t.toY = y
            }
            u.owner = owner
            u.hp = d.readFloat()
            u.task = Task.entries[d.readByte().toInt()]
            u.targetId = d.readInt()
            u.carryType = d.readByte().toInt().takeIf { it >= 0 }?.let { ResourceType.entries[it] }
            u.carryAmount = d.readFloat()
            u.facingLeft = d.readBoolean()
            u.moving = d.readBoolean()
            u.animTime = d.readFloat()
            seenUnits += id
        }
        world.units.filter { it.id !in seenUnits }.forEach {
            world.forget(it)
            tracks.remove(it.id)
        }
        world.units.removeAll { !it.alive }

        // Projectiles
        world.projectiles.clear()
        repeat(d.readShort().toInt()) {
            val p = Projectile(d.readFloat(), d.readFloat(), d.readInt(), GAIA, 0f)
            p.progress = d.readFloat()
            world.projectiles += p
        }

        // Resource nodes
        repeat(d.readShort().toInt()) { (world.entity(d.readInt()) as? ResourceNode)?.let { world.forget(it) } }
        world.nodes.removeAll { !it.alive }
        repeat(d.readShort().toInt()) {
            val id = d.readInt()
            val amount = d.readFloat()
            (world.entity(id) as? ResourceNode)?.amount = amount
        }

        world.rebuildOccupancy()
        // Snapshots arrive every [interval] on average: smooth over that duration.
        if (elapsed > 0.02f) interval = interval * 0.8f + elapsed.coerceIn(0.05f, 0.5f) * 0.2f
        elapsed = 0f
    }

    /** Moves units between the last two snapshots; call every frame. */
    fun interpolate(dt: Float) {
        elapsed += dt
        val a = (elapsed / interval).coerceIn(0f, 1f)
        for (u in world.units) {
            val t = tracks[u.id] ?: continue
            u.x = t.fromX + (t.toX - t.fromX) * a
            u.y = t.fromY + (t.toY - t.fromY) * a
        }
        for (u in world.units) if (u.moving) u.animTime += dt
    }
}
