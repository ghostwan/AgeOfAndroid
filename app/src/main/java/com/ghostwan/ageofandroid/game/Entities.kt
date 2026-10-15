package com.ghostwan.ageofandroid.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val GAIA = -1

/** Base of everything that lives on the map. Coordinates are in tile units (float). */
sealed class Entity(val id: Int, var owner: Int) {
    var hp: Float = 1f
    abstract val maxHp: Float
    abstract val x: Float
    abstract val y: Float
    var alive = true

    /** Distance from a point to the closest point of this entity (0 if inside). */
    abstract fun distanceTo(px: Float, py: Float): Float
}

enum class Task { IDLE, MOVE, GATHER, RETURN, BUILD, ATTACK }

class GameUnit(id: Int, owner: Int, val type: UnitType, override var x: Float, override var y: Float, override val maxHp: Float) :
    Entity(id, owner) {
    var task = Task.IDLE
    var targetId = -1
    var destX = x
    var destY = y
    var path: List<Waypoint> = emptyList()
    var pathIndex = 0
    var repathTimer = 0f
    var carryType: ResourceType? = null
    var carryAmount = 0f
    var cooldown = 0f
    var scanTimer = 0f
    var facingLeft = false
    var moving = false
    var animTime = 0f

    /** Last resource gathered, used to find a new node when the current one is depleted. */
    var lastGatherKind: NodeKind? = null
    var lastGatherFarm = false
    var lastGatherX = 0f
    var lastGatherY = 0f

    /** Stuck detection. */
    var lastProgressX = x
    var lastProgressY = y
    var stuckTimer = 0f

    init {
        hp = maxHp
    }

    override fun distanceTo(px: Float, py: Float) = dist(x, y, px, py)

    fun clearPath() {
        path = emptyList()
        pathIndex = 0
    }
}

class QueueItem(val unit: UnitType? = null, val tech: Tech? = null, val age: Age? = null) {
    val time: Float get() = unit?.trainTime ?: tech?.time ?: age!!.researchTime
    val cost: Cost get() = unit?.cost ?: tech?.cost ?: age!!.cost
    val label: String get() = unit?.label ?: tech?.label ?: age!!.label
    val icon: String get() = unit?.icon ?: tech?.icon ?: "⏫"
}

class Building(
    id: Int,
    owner: Int,
    val type: BuildingType,
    val tx: Int,
    val ty: Int,
    var buildMaxHp: Float,
) : Entity(id, owner) {
    val size get() = type.size
    override val maxHp: Float get() = buildMaxHp
    override val x: Float get() = tx + size / 2f
    override val y: Float get() = ty + size / 2f

    var progress = 0f // 0..1
    val complete get() = progress >= 1f
    val queue = ArrayList<QueueItem>()
    var queueProgress = 0f
    var rallyX = -1f
    var rallyY = -1f
    var cooldown = 0f

    /** Farms carry their own food. */
    var food = 0f

    /** Wonder countdown in seconds (only when complete). */
    var wonderTimer = 0f

    override fun distanceTo(px: Float, py: Float): Float {
        val cx = max(tx.toFloat(), min(px, (tx + size).toFloat()))
        val cy = max(ty.toFloat(), min(py, (ty + size).toFloat()))
        return dist(px, py, cx, cy)
    }

    fun contains(px: Int, py: Int) = px >= tx && px < tx + size && py >= ty && py < ty + size
}

class ResourceNode(id: Int, val kind: NodeKind, val tx: Int, val ty: Int) : Entity(id, GAIA) {
    var amount = kind.amount.toFloat()
    override val maxHp: Float get() = kind.amount.toFloat()
    override val x: Float get() = tx + 0.5f
    override val y: Float get() = ty + 0.5f
    val variant = (tx * 7349 + ty * 1931) and 0xff

    override fun distanceTo(px: Float, py: Float): Float {
        val cx = max(tx.toFloat(), min(px, tx + 1f))
        val cy = max(ty.toFloat(), min(py, ty + 1f))
        return dist(px, py, cx, cy)
    }
}

class Waypoint(val x: Float, val y: Float)

class Projectile(
    var x: Float,
    var y: Float,
    val targetId: Int,
    val damageFrom: Int,
    val damage: Float,
    val speed: Float = 8f,
) {
    var startX = x
    var startY = y
    var alive = true
    var progress = 0f
}

fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}
