package com.ghostwan.ageofandroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.ghostwan.ageofandroid.game.SaveGame
import java.io.File
import com.ghostwan.ageofandroid.game.World

private enum class GestureMode { UNDECIDED, TAP, PAN, BOX }

@Composable
fun GameScreen(world: World, saveFile: File?, onQuit: () -> Unit) {
    val density = LocalDensity.current.density
    val controller = remember(world) { GameController(world, density) }
    GameScreen(controller, onQuit, saveFile)
}

/** Writes the game to [file], or deletes the save once the game is over. Never throws. */
fun persistGame(world: World, file: File?): Boolean {
    if (file == null) return false
    return try {
        if (world.winner != null) file.delete() else SaveGame.save(world, file)
        true
    } catch (e: Exception) {
        Log.e("AgeOfAndroid", "Save failed", e)
        false
    }
}

@Composable
fun GameScreen(controller: GameController, onQuit: () -> Unit, saveFile: File? = null) {
    var showMenu by remember { mutableStateOf(false) }
    fun openMenu() {
        showMenu = true
        controller.paused = true
        persistGame(controller.world, saveFile)
    }

    BackHandler {
        when {
            controller.placing != null -> controller.cancelPlacement()
            showMenu -> {
                showMenu = false
                controller.paused = false
            }
            else -> openMenu()
        }
    }

    // Autosave whenever the app goes to the background (home button, call, screen off...).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { persistGame(controller.world, saveFile) }

    LaunchedEffect(controller) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                controller.tick((now - last) / 1_000_000_000f)
                last = now
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        GameCanvas(controller)
        Hud(controller, onMenu = { openMenu() })
        if (showMenu) {
            PauseMenu(
                controller,
                onResume = {
                    showMenu = false
                    controller.paused = false
                },
                onSave = if (saveFile == null) null else {
                    { persistGame(controller.world, saveFile) }
                },
                onQuit = {
                    persistGame(controller.world, saveFile)
                    onQuit()
                },
            )
        }
        val winner = controller.hud.let { controller.world.winner }
        if (winner != null) {
            // A finished game is not worth resuming.
            LaunchedEffect(Unit) { persistGame(controller.world, saveFile) }
            EndDialog(controller, winner, onQuit)
        }
    }
}

@Composable
private fun GameCanvas(controller: GameController) {
    val haptics = LocalHapticFeedback.current
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { controller.onSizeChanged(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(controller) {
                val slop = viewConfiguration.touchSlop
                val longPress = viewConfiguration.longPressTimeoutMillis
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                var lastTapTime = 0L
                var lastTapAt = Offset.Zero
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = GestureMode.UNDECIDED
                    var tapAt: Offset? = null
                    // Phase 1: decide between tap, pan/pinch, and long-press box selection.
                    val decided = withTimeoutOrNull(longPress) {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.changes.count { it.pressed } > 1) return@withTimeoutOrNull GestureMode.PAN
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull GestureMode.PAN
                            if (!ch.pressed) {
                                tapAt = ch.position
                                return@withTimeoutOrNull GestureMode.TAP
                            }
                            if ((ch.position - down.position).getDistance() > slop) {
                                // Group mode: one finger draws the selection box, two fingers move the map.
                                return@withTimeoutOrNull if (controller.selectMode) GestureMode.BOX else GestureMode.PAN
                            }
                        }
                        @Suppress("UNREACHABLE_CODE")
                        GestureMode.UNDECIDED
                    }
                    mode = decided ?: GestureMode.BOX
                    when (mode) {
                        GestureMode.TAP -> tapAt?.let {
                            val now = System.currentTimeMillis()
                            val double = now - lastTapTime < doubleTapTimeout && (it - lastTapAt).getDistance() < slop * 3
                            if (double) {
                                controller.onDoubleTap(it.x, it.y)
                                lastTapTime = 0L
                            } else {
                                controller.onTap(it.x, it.y)
                                lastTapTime = now
                                lastTapAt = it
                            }
                        }
                        GestureMode.BOX -> {
                            if (!controller.selectMode) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val start = down.position
                            controller.box = Rect(start, start)
                            var end = start
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) break
                                end = ch.position
                                ch.consume()
                                controller.box = Rect(
                                    Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                                    Offset(maxOf(start.x, end.x), maxOf(start.y, end.y)),
                                )
                            }
                            val rect = controller.box
                            controller.box = null
                            if (rect != null) {
                                if (rect.width < slop * 2 && rect.height < slop * 2) controller.onTap(start.x, start.y)
                                else controller.onBoxSelect(rect)
                            }
                        }
                        GestureMode.PAN -> {
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed }) break
                                val zoom = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                val centroid = ev.calculateCentroid()
                                if (zoom != 1f && centroid != Offset.Unspecified) controller.zoom(zoom, centroid.x, centroid.y)
                                controller.pan(pan.x, pan.y)
                                ev.changes.forEach { it.consume() }
                            }
                        }
                        GestureMode.UNDECIDED -> Unit
                    }
                }
            },
    ) {
        controller.frame // read to redraw every frame
        controller.renderer.draw(this)
    }
}
