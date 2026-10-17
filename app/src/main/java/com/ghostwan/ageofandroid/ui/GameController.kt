package com.ghostwan.ageofandroid.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.ghostwan.ageofandroid.game.Age
import com.ghostwan.ageofandroid.game.Building
import com.ghostwan.ageofandroid.game.BuildingType
import com.ghostwan.ageofandroid.game.Command
import com.ghostwan.ageofandroid.game.Cost
import com.ghostwan.ageofandroid.game.Entity
import com.ghostwan.ageofandroid.game.GameUnit
import com.ghostwan.ageofandroid.game.ResourceNode
import com.ghostwan.ageofandroid.game.ResourceType
import com.ghostwan.ageofandroid.game.Task
import com.ghostwan.ageofandroid.game.Tech
import com.ghostwan.ageofandroid.game.UnitType
import com.ghostwan.ageofandroid.game.World
import com.ghostwan.ageofandroid.net.NetHook
import com.ghostwan.ageofandroid.game.apply
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Isometric camera: world tile (x, y) → screen pixels. */
class Camera(private val baseTileW: Float) {
    var zoom = 1f
        private set
    var offsetX = 0f
    var offsetY = 0f

    val tw get() = baseTileW * zoom
    val th get() = tw / 2f

    fun sx(x: Float, y: Float) = (x - y) * tw / 2f + offsetX
    fun sy(x: Float, y: Float) = (x + y) * th / 2f + offsetY

    fun toWorld(px: Float, py: Float): Offset {
        val a = (px - offsetX) / (tw / 2f)
        val b = (py - offsetY) / (th / 2f)
        return Offset((a + b) / 2f, (b - a) / 2f)
    }

    fun centerOn(wx: Float, wy: Float, viewW: Float, viewH: Float) {
        offsetX = viewW / 2f - (wx - wy) * tw / 2f
        offsetY = viewH / 2f - (wx + wy) * th / 2f
    }

    fun zoomAt(factor: Float, fx: Float, fy: Float) {
        val before = toWorld(fx, fy)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Keep the world point under the focus fixed.
        offsetX = fx - (before.x - before.y) * tw / 2f
        offsetY = fy - (before.x + before.y) * th / 2f
    }

    fun clamp(mapSize: Int, viewW: Float, viewH: Float) {
        val c = toWorld(viewW / 2f, viewH / 2f)
        val cx = c.x.coerceIn(0f, mapSize.toFloat())
        val cy = c.y.coerceIn(0f, mapSize.toFloat())
        if (cx != c.x || cy != c.y) centerOn(cx, cy, viewW, viewH)
    }

    companion object {
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 2.6f
    }
}

data class Toast(val id: Long, val text: String, val alert: Boolean, val x: Float, val y: Float, val createdAt: Float)

class Marker(val x: Float, val y: Float, val attack: Boolean, var life: Float = 0.6f)

class GameController(val world: World, density: Float) {
    val camera = Camera(56f * density)
    var viewW = 0f
        private set
    var viewH = 0f
        private set
    private var centered = false

    /** Bumped every frame: the map canvas reads it to redraw. */
    var frame by mutableLongStateOf(0L)
        private set

    /** Bumped ~10 times per second and on user actions: HUD composables read it to recompose. */
    var hud by mutableIntStateOf(0)
        private set

    var paused by mutableStateOf(false)
    var bottomBarVisible by mutableStateOf(true)
    var speed by mutableFloatStateOf(1f)
    var placing by mutableStateOf<BuildingType?>(null)
        private set
    var ghostTx = 0
        private set
    var ghostTy = 0
        private set
    var box: Rect? = null
    var lastError by mutableStateOf<String?>(null)

    /**
     * Subject of the info card ([BuildingType], [UnitType], [Tech] or [Age]): shown on long press, or when
     * a button is tapped but its action is not possible yet (locked or too expensive).
     */
    var info by mutableStateOf<Any?>(null)
    val toasts = mutableStateListOf<Toast>()
    val markers = ArrayList<Marker>()
    /** The local player. */
    val me get() = world.viewer

    /**
     * Where player orders go. Solo and multiplayer host: applied to the local world right away.
     * Multiplayer guest: sent to the host (see the net package).
     */
    var sendCommand: (Command) -> Unit = { cmd -> world.apply(cmd)?.let { showError(it) } }

    /** False on a multiplayer guest: the host runs the simulation. */
    var simulate = true

    /** Speed and pause are disabled in multiplayer. */
    var multiplayer = false

    /** Multiplayer session plugged into the game loop. */
    var netHook: NetHook? = null
    private var lastEventSeq = world.lastEventSeq

    private val selection = LinkedHashSet<Int>()

    /**
     * Group selection mode: one finger draws a selection box right away (no long press) and tapping
     * a unit adds it to / removes it from the selection. Ends on the next order given on the map.
     */
    var selectMode by mutableStateOf(false)
    private var hudTimer = 0f
    private var toastId = 0L
    private var errorTimer = 0f
    val renderer = GameRenderer(this)

    // ------------------------------------------------------------------ Loop

    fun tick(dtRaw: Float) {
        val dt = min(dtRaw, 0.1f)
        netHook?.beforeTick(dt)
        if (simulate && !paused && world.winner == null) {
            var remaining = dt * speed
            while (remaining > 0f) {
                val step = min(remaining, 0.05f)
                world.update(step)
                remaining -= step
            }
        }
        netHook?.afterTick(dt)
        markers.forEach { it.life -= dt }
        markers.removeAll { it.life <= 0f }
        for (e in world.eventsFor(me, lastEventSeq)) {
            toasts += Toast(toastId++, e.text, e.alert, e.x, e.y, world.time)
            while (toasts.size > 4) toasts.removeAt(0)
        }
        lastEventSeq = world.lastEventSeq
        toasts.removeAll { world.time - it.createdAt > 6f || it.createdAt > world.time }
        if (errorTimer > 0f) {
            errorTimer -= dt
            if (errorTimer <= 0f) lastError = null
        }
        selection.removeAll { world.entity(it) == null }
        frame++
        hudTimer += dt
        if (hudTimer > 0.1f) {
            hudTimer = 0f
            hud++
        }
    }

    fun invalidateHud() {
        hud++
    }

    fun onSizeChanged(w: Float, h: Float) {
        viewW = w
        viewH = h
        if (!centered && w > 0f) {
            centered = true
            val p = world.human
            camera.centerOn(p.startX + 0.5f, p.startY + 2f, w, h)
        }
    }

    fun jumpTo(x: Float, y: Float) {
        camera.centerOn(x, y, viewW, viewH)
        camera.clamp(world.map.width, viewW, viewH)
    }

    fun pan(dx: Float, dy: Float) {
        camera.offsetX += dx
        camera.offsetY += dy
        camera.clamp(world.map.width, viewW, viewH)
    }

    fun zoom(factor: Float, fx: Float, fy: Float) {
        camera.zoomAt(factor, fx, fy)
        camera.clamp(world.map.width, viewW, viewH)
    }

    fun showError(msg: String) {
        lastError = msg
        errorTimer = 2.5f
    }

    // ------------------------------------------------------------------ Selection

    fun isSelected(id: Int) = id in selection

    fun selectedEntities(): List<Entity> = selection.mapNotNull { world.entity(it) }

    fun selectedUnits(): List<GameUnit> = selectedEntities().filterIsInstance<GameUnit>().filter { it.owner == me }

    fun selectedBuilding(): Building? = selectedEntities().singleOrNull() as? Building

    fun select(entities: Collection<Entity>) {
        info = null
        selection.clear()
        entities.forEach { selection += it.id }
        // Selecting something unfolds the bottom bar so its details and actions are visible.
        if (selection.isNotEmpty()) bottomBarVisible = true
        invalidateHud()
    }

    fun clearSelection() {
        selection.clear()
        invalidateHud()
    }

    fun selectIdleVillager() {
        val idle = world.units.filter { it.alive && it.owner == me && it.type == UnitType.VILLAGER && it.task == Task.IDLE }
        if (idle.isEmpty()) return
        val current = selectedUnits().singleOrNull()
        val idx = if (current != null) (idle.indexOf(current) + 1) % idle.size else 0
        val v = idle[idx]
        select(listOf(v))
        jumpTo(v.x, v.y)
    }

    fun selectArmy() {
        val army = world.units.filter { it.alive && it.owner == me && it.type.isMilitary }
        if (army.isEmpty()) return
        select(army)
        jumpTo(army.map { it.x }.average().toFloat(), army.map { it.y }.average().toFloat())
    }

    fun selectTownCenter() {
        val tc = world.buildings.firstOrNull { it.alive && it.owner == me && it.type == BuildingType.TOWN_CENTER } ?: return
        select(listOf(tc))
        jumpTo(tc.x, tc.y)
    }

    fun idleVillagerCount() = world.units.count { it.alive && it.owner == me && it.type == UnitType.VILLAGER && it.task == Task.IDLE }

    fun armyCount() = world.units.count { it.alive && it.owner == me && it.type.isMilitary }

    // ------------------------------------------------------------------ Input

    fun onTap(px: Float, py: Float) {
        info = null
        val w = camera.toWorld(px, py)
        placing?.let { type ->
            moveGhost(type, w.x, w.y)
            return
        }
        val hit = renderer.hitTest(px, py)
        if (selectMode) {
            if (hit is GameUnit && hit.owner == me) {
                if (!selection.remove(hit.id)) {
                    // Units only: drop a selected building when starting a group.
                    selection.removeAll { world.entity(it) !is GameUnit }
                    selection += hit.id
                    bottomBarVisible = true
                }
                invalidateHud()
                return
            }
            // Anything else is the order for the group: leave the mode and handle the tap normally.
            selectMode = false
        }
        val units = selectedUnits()
        val building = selectedBuilding()?.takeIf { it.owner == me }
        when {
            hit is GameUnit && hit.owner == me -> select(listOf(hit))
            // Tapping one of our buildings selects it, unless the villagers have an obvious job there:
            // finishing a construction, repairing it or working a free farm (drop-offs happen automatically).
            units.isNotEmpty() && hit is Building && hit.owner == me &&
                !(units.any { it.type == UnitType.VILLAGER } && (!hit.complete || world.needsRepair(hit) || hit.type == BuildingType.FARM && !world.farmTaken(hit))) ->
                select(listOf(hit))
            units.isNotEmpty() -> {
                sendCommand(Command.Smart(me, units.map { it.id }, w.x, w.y, hit?.id ?: -1))
                val attack = hit != null && world.isEnemy(me, hit.owner)
                markers += Marker(hit?.x ?: w.x, hit?.y ?: w.y, attack)
                invalidateHud()
            }
            building != null && building.complete && building.type.trains.isNotEmpty() && (hit == null || hit is ResourceNode) -> {
                sendCommand(Command.Rally(me, building.id, w.x, w.y))
                markers += Marker(w.x, w.y, false)
                invalidateHud()
            }
            hit != null -> select(listOf(hit))
            else -> clearSelection()
        }
    }

    fun onBoxSelect(rect: Rect) {
        val inBox = world.units.filter { u ->
            u.alive && u.owner == me && rect.contains(Offset(camera.sx(u.x, u.y), camera.sy(u.x, u.y)))
        }
        if (inBox.isNotEmpty()) {
            // Like on PC, prefer military units when mixed with villagers.
            val military = inBox.filter { it.type.isMilitary }
            val picked = if (military.isNotEmpty() && military.size != inBox.size) military else inBox
            // In group mode boxes add up, so several groups can be gathered before giving the order.
            select(if (selectMode) selectedUnits() + picked else picked)
        }
    }

    /** Double tap on one of our units: selects every unit of that type visible on screen. */
    fun onDoubleTap(px: Float, py: Float) {
        if (placing != null) return
        val hit = renderer.hitTest(px, py) as? GameUnit ?: return
        if (hit.owner != me) return
        val sameType = world.units.filter { u ->
            u.alive && u.owner == me && u.type == hit.type &&
                camera.sx(u.x, u.y) in 0f..viewW && camera.sy(u.x, u.y) in 0f..viewH
        }
        select(if (selectMode) selectedUnits() + sameType else sameType)
    }

    // ------------------------------------------------------------------ Building placement

    fun startPlacement(type: BuildingType) {
        val p = world.human
        if (p.age < type.minAge || !p.res.canAfford(p.buildingCost(type))) {
            info = type
            return
        }
        info = null
        placing = type
        val c = camera.toWorld(viewW / 2f, viewH / 2f)
        moveGhost(type, c.x, c.y)
        invalidateHud()
    }

    private fun moveGhost(type: BuildingType, wx: Float, wy: Float) {
        ghostTx = floor(wx - type.size / 2f + 0.5f).toInt().coerceIn(0, world.map.width - type.size)
        ghostTy = floor(wy - type.size / 2f + 0.5f).toInt().coerceIn(0, world.map.height - type.size)
        invalidateHud()
    }

    fun ghostValid(): Boolean = placing?.let { world.canPlaceFor(me, it, ghostTx, ghostTy) } ?: false

    fun confirmPlacement(keepPlacing: Boolean = false) {
        val type = placing ?: return
        val p = world.human
        val cost = p.buildingCost(type)
        if (!p.res.canAfford(cost)) {
            showError("Ressources insuffisantes")
            return
        }
        if (!ghostValid()) {
            showError("Emplacement invalide")
            return
        }
        val builders = selectedUnits().filter { it.type == UnitType.VILLAGER }
        sendCommand(Command.Place(me, type, ghostTx, ghostTy, builders.map { it.id }))
        markers += Marker(ghostTx + type.size / 2f, ghostTy + type.size / 2f, false)
        // Resources of a guest only update with the next snapshot: count this building's cost already.
        val canAffordAnother = ResourceType.entries.all { p.res[it] - (if (simulate) 0 else cost[it]) >= cost[it] }
        if (!keepPlacing || !canAffordAnother) placing = null
        invalidateHud()
    }

    fun cancelPlacement() {
        placing = null
        invalidateHud()
    }

    // ------------------------------------------------------------------ Orders from the panel

    private fun order(cmd: Command) {
        sendCommand(cmd)
        invalidateHud()
    }

    /** Opens the info card instead of failing when the player lacks the age or the resources. */
    private fun explainIfBlocked(subject: Any, minAge: Age, cost: Cost): Boolean {
        val p = world.human
        if (p.age >= minAge && p.res.canAfford(cost)) return false
        info = subject
        invalidateHud()
        return true
    }

    fun train(type: UnitType) {
        val b = selectedBuilding() ?: return
        if (explainIfBlocked(type, type.minAge, type.cost)) return
        info = null
        order(Command.Train(me, b.id, type))
    }

    fun research(tech: Tech) {
        val b = selectedBuilding() ?: return
        if (explainIfBlocked(tech, tech.minAge, tech.cost)) return
        info = null
        order(Command.Research(me, b.id, tech))
    }

    fun advanceAge() {
        val b = selectedBuilding() ?: return
        val next = world.human.age.next ?: return
        if (explainIfBlocked(next, Age.DARK, next.cost)) return
        info = null
        order(Command.AgeUp(me, b.id))
    }

    fun cancelQueue(index: Int) {
        val b = selectedBuilding() ?: return
        order(Command.CancelQueue(me, b.id, index))
    }

    fun stop() {
        order(Command.Stop(me, selectedUnits().map { it.id }))
    }

    fun deleteSelected() {
        sendCommand(Command.Delete(me, selectedEntities().filter { it.owner == me }.map { it.id }))
        clearSelection()
    }

    fun villagerCountsByResource(): IntArray {
        val counts = IntArray(4)
        world.units.forEach { u ->
            if (!u.alive || u.owner != me || u.type != UnitType.VILLAGER) return@forEach
            if (u.task != Task.GATHER && u.task != Task.RETURN) return@forEach
            val t = when (val target = world.entity(u.targetId)) {
                is ResourceNode -> target.kind.resource
                is Building -> if (target.type == BuildingType.FARM) ResourceType.FOOD else u.carryType
                else -> u.carryType
            } ?: return@forEach
            counts[t.ordinal]++
        }
        return counts
    }

    fun minimapTap(nx: Float, ny: Float) {
        // nx, ny are world tile coordinates.
        jumpTo(max(0f, nx), max(0f, ny))
    }
}
