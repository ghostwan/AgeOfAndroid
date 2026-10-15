package com.ghostwan.ageofandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostwan.ageofandroid.game.Civ
import com.ghostwan.ageofandroid.game.Difficulty
import com.ghostwan.ageofandroid.game.GameConfig

@Composable
fun AgeOfAndroidApp() {
    MaterialTheme(colorScheme = darkColorScheme(primary = BorderGold, background = Color(0xFF1B140C))) {
        var config by rememberSaveable(stateSaver = configSaver) { mutableStateOf<GameConfig?>(null) }
        val current = config
        if (current == null) {
            MenuScreen(onStart = { config = it })
        } else {
            GameScreen(current, onQuit = { config = null })
        }
    }
}

private val configSaver = androidx.compose.runtime.saveable.Saver<GameConfig?, List<Any>>(
    save = { c -> c?.let { listOf(it.civ.ordinal, it.opponents, it.difficulty.ordinal, it.mapSize, it.seed) } ?: emptyList() },
    restore = { l ->
        if (l.isEmpty()) {
            null
        } else {
            GameConfig(Civ.entries[l[0] as Int], l[1] as Int, Difficulty.entries[l[2] as Int], l[3] as Int, l[4] as Long)
        }
    },
)

private val MAP_SIZES = listOf(56 to "Petite", 72 to "Moyenne", 96 to "Grande")

@Composable
fun MenuScreen(onStart: (GameConfig) -> Unit) {
    var civ by rememberSaveable { mutableStateOf(Civ.FRANKS) }
    var opponents by rememberSaveable { mutableStateOf(1) }
    var difficulty by rememberSaveable { mutableStateOf(Difficulty.NORMAL) }
    var mapSize by rememberSaveable { mutableStateOf(72) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A5A2A), Color(0xFF1B140C)))),
    ) {
        MenuBackdrop()
        Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("AGE OF", color = TextCream, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                androidx.compose.material3.Text(
                    "ANDROID",
                    color = BorderGold,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                )
                Text("Bâtissez un empire, de l'Âge sombre à l'Âge impérial", color = TextDim, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                MenuButton("⚔️  Jouer") { onStart(GameConfig(civ, opponents, difficulty, mapSize, System.currentTimeMillis())) }
                Spacer(Modifier.height(12.dp))
                MenuButton(if (showHelp) "✖  Fermer l'aide" else "📖  Comment jouer") { showHelp = !showHelp }
            }
            Column(
                Modifier
                    .width(420.dp)
                    .background(PanelColor, RoundedCornerShape(12.dp))
                    .border(2.dp, BorderGold, RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (showHelp) {
                    Text("Comment jouer", color = BorderGold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(HELP_TEXT, fontSize = 13.sp)
                } else {
                    Text("Civilisation", color = BorderGold, fontWeight = FontWeight.Bold)
                    Civ.entries.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { c -> Chip(c.label, civ == c, Modifier.weight(1f)) { civ = c } }
                        }
                    }
                    Text("Bonus : ${civ.bonus}", color = TextDim, fontSize = 12.sp)
                    Text("Adversaires (IA)", color = BorderGold, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..3).forEach { n -> Chip("$n", opponents == n, Modifier.weight(1f)) { opponents = n } }
                    }
                    Text("Difficulté", color = BorderGold, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Difficulty.entries.forEach { d -> Chip(d.label, difficulty == d, Modifier.weight(1f)) { difficulty = d } }
                    }
                    Text("Taille de la carte", color = BorderGold, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MAP_SIZES.forEach { (s, label) -> Chip(label, mapSize == s, Modifier.weight(1f)) { mapSize = s } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF7A5A20) else Color(0xFF3A2A18))
            .border(1.dp, if (selected) BorderGold else BorderGold.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) TextCream else TextDim, fontWeight = if (selected) FontWeight.Bold else null, fontSize = 14.sp)
    }
}

/** Simple silhouette landscape for the title screen. */
@Composable
private fun MenuBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawCircle(Color(0x55FFD27A), h * 0.18f, Offset(w * 0.2f, h * 0.3f))
        // Hills
        drawCircle(Color(0xFF2A4420), w * 0.5f, Offset(w * 0.15f, h * 1.25f))
        drawCircle(Color(0xFF223A1A), w * 0.55f, Offset(w * 0.8f, h * 1.35f))
        // Castle silhouette
        val base = h * 0.92f
        val cx = w * 0.08f
        val c = Color(0xFF15100A)
        drawRect(c, Offset(cx - 90f, base - 120f), androidx.compose.ui.geometry.Size(180f, 120f))
        drawRect(c, Offset(cx - 120f, base - 180f), androidx.compose.ui.geometry.Size(50f, 180f))
        drawRect(c, Offset(cx + 70f, base - 180f), androidx.compose.ui.geometry.Size(50f, 180f))
        drawRect(c, Offset(cx - 30f, base - 230f), androidx.compose.ui.geometry.Size(60f, 110f))
        for (i in 0 until 4) {
            drawRect(c, Offset(cx - 120f + i * 14f, base - 196f), androidx.compose.ui.geometry.Size(8f, 16f))
            drawRect(c, Offset(cx + 70f + i * 14f, base - 196f), androidx.compose.ui.geometry.Size(8f, 16f))
        }
        drawRect(Color(0xFF2F6FE0), Offset(cx - 2f, base - 290f), androidx.compose.ui.geometry.Size(4f, 60f))
        drawRect(Color(0xFF2F6FE0), Offset(cx + 2f, base - 290f), androidx.compose.ui.geometry.Size(36f, 20f))
    }
}
