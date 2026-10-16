package com.ghostwan.ageofandroid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ghostwan.ageofandroid.game.SaveGame
import com.ghostwan.ageofandroid.game.GameConfig
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
        compose.runOnUiThread {
            controller.bottomBarVisible = true
            controller.select(listOf(controller.world.units.first { it.owner == 0 && it.type.name == "VILLAGER" }))
        }
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
}
