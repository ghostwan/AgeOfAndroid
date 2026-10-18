package com.ghostwan.ageofandroid.ui

import com.ghostwan.ageofandroid.BuildConfig
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.getBoundsInRoot
import com.ghostwan.ageofandroid.game.SaveGame
import com.ghostwan.ageofandroid.game.GameConfig
import com.ghostwan.ageofandroid.game.MapType
import com.ghostwan.ageofandroid.game.BuildingType
import com.ghostwan.ageofandroid.game.UnitType
import com.ghostwan.ageofandroid.game.dist
import com.ghostwan.ageofandroid.game.Terrain
import com.ghostwan.ageofandroid.game.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Smoke test rendering the real screens with Robolectric native graphics.
 * Screenshots are written to app/build/screenshots for a quick visual check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w891dp-h411dp-land-xhdpi", sdk = [34])
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun menu() {
        compose.setContent { MenuScreen(onStart = {}) }
        capture("menu")
    }

    @Test
    fun mapHighlands() = mapType(MapType.HIGHLANDS, Terrain.MOUNTAIN)

    @Test
    fun mapCanyons() = mapType(MapType.CANYONS, Terrain.CANYON)

    @Test
    fun mapLakes() = mapType(MapType.LAKES, Terrain.WATER)

    @Test
    fun dockAndBoats() {
        compose.mainClock.autoAdvance = false
        val world = World(GameConfig(opponents = 1, mapSize = 72, seed = 3L, mapType = MapType.LAKES))
        world.revealAll = true
        world.updateFog()
        val me = world.players[0]
        val (dx, dy) = (0 until 72 * 72).map { it % 72 to it / 72 }
            .filter { (x, y) -> world.canPlaceFor(0, BuildingType.DOCK, x, y) }
            .minBy { (x, y) -> kotlin.math.hypot((x - me.startX).toDouble(), (y - me.startY).toDouble()) }
        val dock = world.addBuilding(0, BuildingType.DOCK, dx, dy, complete = true)
        repeat(2) { world.train(dock, UnitType.FISHING_BOAT) }
        world.human.res.add(com.ghostwan.ageofandroid.game.ResourceType.WOOD, 200f)
        world.forceAdvance(65f)
        world.units.filter { it.type == UnitType.FISHING_BOAT }.forEach { boat ->
            world.nodes.filter { it.kind == com.ghostwan.ageofandroid.game.NodeKind.FISH }.minByOrNull { dist(it.x, it.y, boat.x, boat.y) }
                ?.let { world.commandSmart(listOf(boat), it.x, it.y, it) }
        }
        world.forceAdvance(12f)
        val controller = GameController(world, 2f)
        compose.setContent { GameScreen(controller, onQuit = {}) }
        repeat(5) { compose.mainClock.advanceTimeBy(100) }
        val view = compose.activity.window.decorView
        controller.camera.zoomAt(1.2f, view.width / 2f, view.height / 2f)
        controller.camera.centerOn(dock.x, dock.y, view.width.toFloat(), view.height.toFloat() * 0.6f)
        controller.select(listOf(dock))
        repeat(5) { compose.mainClock.advanceTimeBy(100) }
        capture("dock_boats")
    }

    private fun mapType(type: MapType, terrain: Terrain) {
        run {
            compose.mainClock.autoAdvance = false
            val world = World(GameConfig(opponents = 1, mapSize = 72, seed = 4L, mapType = type))
            world.revealAll = true
            world.updateFog()
            val controller = GameController(world, 2f)
            compose.setContent { GameScreen(controller, onQuit = {}) }
            repeat(5) { compose.mainClock.advanceTimeBy(100) }
            val view = compose.activity.window.decorView
            val me = world.players[0]
            // Frame the base and the nearest obstacle.
            val i = world.map.terrain.indices.filter { world.map.terrain[it] == terrain }
                .minBy { kotlin.math.hypot((it % 72 - me.startX).toDouble(), (it / 72 - me.startY).toDouble()) }
            val cx = (me.startX + i % 72) / 2f
            val cy = (me.startY + i / 72) / 2f
            controller.camera.zoomAt(0.7f, view.width / 2f, view.height / 2f)
            controller.camera.centerOn(cx, cy, view.width.toFloat(), view.height.toFloat())
            repeat(5) { compose.mainClock.advanceTimeBy(100) }
            capture("map_${type.name.lowercase()}")
        }
    }

    @Test
    fun menuChangelog() {
        compose.setContent { MenuScreen(onStart = {}) }
        compose.onNodeWithText("Nouveautés", substring = true).performClick()
        compose.onNodeWithText("v${BuildConfig.VERSION_NAME} (installée)", substring = true).assertExists()
        capture("menu_changelog")
    }

    @Test
    fun game() {
        compose.mainClock.autoAdvance = false
        val controller = GameController(World(GameConfig(opponents = 1, mapSize = 56, seed = 7L)), 2f)
        compose.setContent { GameScreen(controller, onQuit = {}) }
        repeat(20) { compose.mainClock.advanceTimeBy(100) }
        capture("game_start")
        repeat(300) { compose.mainClock.advanceTimeBy(100) }
        assertTrue(controller.world.time > 25f)
        compose.onNodeWithText("00:3", substring = true).assertExists()
        capture("game_30s")
        compose.runOnUiThread { controller.select(controller.world.units.filter { it.owner == 0 && it.type.name == "VILLAGER" }) }
        repeat(5) { compose.mainClock.advanceTimeBy(100) }
        capture("game_selection")
        compose.runOnUiThread {
            val tc = controller.world.buildings.first { it.owner == 0 }
            controller.world.train(tc, com.ghostwan.ageofandroid.game.UnitType.VILLAGER)
            controller.world.train(tc, com.ghostwan.ageofandroid.game.UnitType.VILLAGER)
            controller.select(listOf(tc))
        }
        repeat(30) { compose.mainClock.advanceTimeBy(100) }
        capture("game_town_center")
    }

    /** Small phone with a large system font: the selection panel must still fit. */
    @Test
    @Config(qualifiers = "w780dp-h360dp-land-xxhdpi")
    fun smallPhoneLargeFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        compose.mainClock.autoAdvance = false
        val controller = GameController(World(GameConfig(opponents = 1, mapSize = 56, seed = 7L)), 3f)
        compose.setContent { FontScaleCap { GameScreen(controller, onQuit = {}) } }
        repeat(10) { compose.mainClock.advanceTimeBy(100) }
        compose.runOnUiThread {
            val tc = controller.world.buildings.first { it.owner == 0 }
            controller.world.train(tc, com.ghostwan.ageofandroid.game.UnitType.VILLAGER)
            controller.select(listOf(tc))
        }
        repeat(30) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithText("PV", substring = true).assertIsDisplayed()
        // The countdown must follow the engine (it used to stay frozen on the HUD).
        val tc = controller.world.buildings.first { it.owner == 0 }
        val remaining = (tc.queue.first().time - tc.queueProgress).toInt() + 1
        compose.onNodeWithText("Villageois : ${remaining}s").assertIsDisplayed()
        capture("small_phone_town_center")
        compose.runOnUiThread { controller.bottomBarVisible = false }
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithText("▲", substring = true).assertIsDisplayed()
        capture("small_phone_bar_hidden")
        // Selecting something unfolds the bar again.
        compose.runOnUiThread {
            controller.select(listOf(controller.world.units.first { it.owner == 0 && it.type.name == "VILLAGER" }))
        }
        assertTrue(controller.bottomBarVisible)
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        capture("small_phone_villager")
    }

    @Test
    fun tappingOwnBuildingWithVillagerSelectedSelectsIt() {
        compose.mainClock.autoAdvance = false
        val controller = GameController(World(GameConfig(opponents = 1, mapSize = 56, seed = 7L)), 2f)
        compose.setContent { GameScreen(controller, onQuit = {}) }
        repeat(5) { compose.mainClock.advanceTimeBy(100) }
        compose.runOnUiThread {
            val world = controller.world
            val villager = world.units.first { it.owner == 0 && it.type.name == "VILLAGER" }
            val tc = world.buildings.first { it.owner == 0 }
            controller.select(listOf(villager))
            controller.onTap(controller.camera.sx(tc.x, tc.y), controller.camera.sy(tc.x, tc.y))
            assertTrue(controller.selectedBuilding() === tc)
            assertTrue(villager.task.name == "IDLE")
        }
    }

    @Test
    fun pauseMenuSavesAndMenuOffersToContinue() {
        val saveFile = File(compose.activity.filesDir, "test_save.bin").apply { delete() }
        compose.mainClock.autoAdvance = false
        val controller = GameController(World(GameConfig(opponents = 2, mapSize = 56, seed = 5L)), 2f)
        compose.setContent { GameScreen(controller, onQuit = {}, saveFile = saveFile) }
        repeat(50) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithText("☰").performClick()
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        assertTrue(saveFile.exists())
        capture("pause_menu")
        val loaded = SaveGame.load(saveFile)
        assertEquals(controller.world.time, loaded.time, 0.2f)
        assertEquals(controller.world.units.size, loaded.units.size)
    }

    @Test
    fun menuWithSave() {
        val world = World(GameConfig(opponents = 2, mapSize = 56, seed = 5L))
        world.forceAdvance(125f)
        val file = File(compose.activity.filesDir, "menu_save.bin")
        SaveGame.save(world, file)
        compose.setContent { MenuScreen(onStart = {}, save = SaveGame.info(file), onContinue = {}) }
        compose.onNodeWithText("▶  Continuer").assertIsDisplayed()
        capture("menu_continue")
    }

    @Test
    @Config(qualifiers = "w780dp-h360dp-land-xhdpi", fontScale = 1.3f)
    fun menuWithSaveSmallPhone() {
        val world = World(GameConfig(opponents = 1, mapSize = 56, seed = 5L))
        val file = File(compose.activity.filesDir, "menu_save_small.bin")
        SaveGame.save(world, file)
        compose.setContent { FontScaleCap { MenuScreen(onStart = {}, save = SaveGame.info(file), onContinue = {}) } }
        val screen = compose.onRoot().getBoundsInRoot()
        val help = compose.onNodeWithText("📖  Comment jouer").getBoundsInRoot()
        assertTrue("help button cut: $help in $screen", help.bottom <= screen.bottom)
        capture("menu_continue_small")
    }

    @Test
    fun buildingInfo() {
        compose.mainClock.autoAdvance = false
        val controller = GameController(World(GameConfig(opponents = 1, mapSize = 56, seed = 7L)), 2f)
        compose.setContent { GameScreen(controller, onQuit = {}) }
        compose.runOnUiThread {
            controller.select(controller.world.units.filter { it.owner == 0 && it.type.name == "VILLAGER" }.take(1))
            controller.startPlacement(com.ghostwan.ageofandroid.game.BuildingType.STABLE)
        }
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        assertEquals(com.ghostwan.ageofandroid.game.BuildingType.STABLE, controller.info)
        compose.onNodeWithText("Disponible à partir", substring = true).assertExists()
        capture("building_info_locked")
        compose.runOnUiThread { controller.startPlacement(com.ghostwan.ageofandroid.game.BuildingType.MILL) }
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        assertEquals(null, controller.info)
        compose.onNodeWithText("Dépôt de nourriture", substring = true).assertExists()
        capture("building_placement_info")
        compose.runOnUiThread { controller.info = com.ghostwan.ageofandroid.game.UnitType.SPEARMAN }
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithText("anti-cavalerie", substring = true).assertExists()
        compose.onNodeWithText("+15 contre cavalerie", substring = true).assertExists()
        capture("unit_info_spearman")
        compose.runOnUiThread { controller.info = com.ghostwan.ageofandroid.game.Age.FEUDAL }
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        capture("age_info_feudal")
    }

    @Test
    fun groupSelection() {
        compose.mainClock.autoAdvance = false
        val controller = GameController(World(GameConfig(opponents = 1, mapSize = 56, seed = 7L)), 2f)
        compose.setContent { GameScreen(controller, onQuit = {}) }
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        val villagers = controller.world.units.filter { it.owner == 0 && it.type.name == "VILLAGER" }
        val cam = controller.camera
        fun tapPoint(u: com.ghostwan.ageofandroid.game.GameUnit) = Pair(cam.sx(u.x, u.y), cam.sy(u.x, u.y) - cam.tw * 0.25f)

        // Double tap on a villager selects every villager on screen.
        compose.runOnUiThread {
            val (x, y) = tapPoint(villagers[0])
            controller.onDoubleTap(x, y)
        }
        assertEquals(villagers.size, controller.selectedUnits().size)

        // Group mode: taps toggle units in and out of the selection.
        compose.runOnUiThread {
            controller.clearSelection()
            controller.selectMode = true
            val (x0, y0) = tapPoint(villagers[0])
            controller.onTap(x0, y0)
            val (x1, y1) = tapPoint(villagers[1])
            controller.onTap(x1, y1)
        }
        assertEquals(2, controller.selectedUnits().size)
        repeat(3) { compose.mainClock.advanceTimeBy(100) }
        compose.onNodeWithText("Mode groupe", substring = true).assertExists()
        capture("group_mode")
        compose.runOnUiThread {
            val (x1, y1) = tapPoint(villagers[1])
            controller.onTap(x1, y1)
        }
        assertEquals(1, controller.selectedUnits().size)

        // Tapping the ground gives the order and leaves the mode.
        compose.runOnUiThread { controller.onTap(10f, controller.viewH / 2f) }
        assertEquals(false, controller.selectMode)
        assertEquals(1, controller.selectedUnits().size)
    }

    @Test
    fun multiplayerScreens() {
        compose.setContent { MultiplayerScreen(discovery = null, onBack = {}, onHosted = {}, onJoined = {}) }
        compose.onNodeWithText("Créer une partie", substring = true).assertExists()
        capture("mp_home")

        val host = com.ghostwan.ageofandroid.net.HostSession("Erwan", com.ghostwan.ageofandroid.game.Civ.FRANKS, port = 0)
        val guest = com.ghostwan.ageofandroid.net.GuestSession(
            com.ghostwan.ageofandroid.net.TcpLink.connect("127.0.0.1", host.port), "Pixel", com.ghostwan.ageofandroid.game.Civ.BRITONS,
        )
        try {
            val end = System.currentTimeMillis() + 5000
            while (host.lobby.seats.size < 2 || guest.lobby == null) {
                check(System.currentTimeMillis() < end)
                host.poll()
                guest.poll()
                Thread.sleep(10)
            }
            host.addAi()
            host.setMode(com.ghostwan.ageofandroid.net.GameMode.COOP)
            var started = false
            compose.runOnUiThread { compose.activity.setContent { FontScaleCap { HostLobbyScreen(host, null, onStart = { started = true }, onCancel = {}, localAddresses = { listOf("192.168.1.20") }) } } }
            compose.waitForIdle()
            compose.onNodeWithText("Pixel", substring = true).assertExists()
            capture("mp_host_lobby")
            compose.onNodeWithText("Lancer la partie", substring = true).performClick()
            assertTrue(started)

            while (guest.lobby?.seats?.size != 3) {
                host.poll()
                guest.poll()
                Thread.sleep(10)
            }
            compose.runOnUiThread { compose.activity.setContent { FontScaleCap { GuestLobbyScreen(guest, onPlaying = {}, onLeave = {}) } } }
            compose.waitForIdle()
            compose.onNodeWithText("En attente", substring = true).assertExists()
            capture("mp_guest_lobby")

            // Guest in game.
            host.start(seed = 3L)
            while (guest.state != com.ghostwan.ageofandroid.net.GuestSession.State.PLAYING) {
                host.poll()
                guest.poll()
                Thread.sleep(10)
            }
            val controller = GameController(guest.world!!, 2f).apply {
                multiplayer = true
                simulate = false
                netHook = guest
                sendCommand = { guest.send(it) }
            }
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { compose.activity.setContent { FontScaleCap { GameScreen(controller, onQuit = {}) } } }
            repeat(10) { compose.mainClock.advanceTimeBy(100) }
            capture("mp_guest_game")
            assertEquals(1, controller.me)
        } finally {
            guest.close()
            host.close()
        }
    }
}
