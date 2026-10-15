package com.ghostwan.ageofandroid.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.ghostwan.ageofandroid.game.GameConfig
import com.ghostwan.ageofandroid.game.World
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
    }
}
