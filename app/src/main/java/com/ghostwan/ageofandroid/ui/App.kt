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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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
import com.ghostwan.ageofandroid.game.SaveGame
import com.ghostwan.ageofandroid.game.SaveInfo
import com.ghostwan.ageofandroid.game.World
import com.ghostwan.ageofandroid.net.GuestSession
import com.ghostwan.ageofandroid.net.HostSession
import com.ghostwan.ageofandroid.net.JoinLink
import com.ghostwan.ageofandroid.net.LanDiscovery
import java.io.File

/** @param pendingJoin game to join from a scanned QR code (handled from the menus, not mid-game). */
@Composable
fun AgeOfAndroidApp(pendingJoin: JoinLink? = null, onPendingJoinHandled: () -> Unit = {}) {
    FontScaleCap { AppContent(pendingJoin, onPendingJoinHandled) }
}

/** Game HUDs have a fixed layout: cap the system font scale so large fonts don't push content out. */
@Composable
fun FontScaleCap(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val capped = Density(density.density, fontScale = density.fontScale.coerceAtMost(MAX_FONT_SCALE))
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

private const val MAX_FONT_SCALE = 1.1f

private sealed interface Screen {
    data object Menu : Screen
    data class Solo(val world: World) : Screen
    data object MultiplayerHome : Screen
    data class HostLobby(val session: HostSession) : Screen
    data class GuestLobby(val session: GuestSession) : Screen
    data class MultiplayerGame(val controller: GameController, val host: HostSession?, val guest: GuestSession?) : Screen
}

@Composable
private fun AppContent(pendingJoin: JoinLink?, onPendingJoinHandled: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = BorderGold, background = Color(0xFF1B140C))) {
        val context = LocalContext.current
        val density = LocalDensity.current.density
        val saveFile = remember { File(context.filesDir, SAVE_FILE) }
        val discovery = remember {
            try {
                LanDiscovery(context)
            } catch (e: Exception) {
                null
            }
        }
        var screen by remember { mutableStateOf<Screen>(Screen.Menu) }
        var error by remember { mutableStateOf<String?>(null) }
        // A QR code scanned with the camera: go join it, unless a game or lobby is in progress.
        LaunchedEffect(pendingJoin, screen) {
            if (pendingJoin != null && screen == Screen.Menu) screen = Screen.MultiplayerHome
        }
        when (val current = screen) {
            Screen.Menu -> {
                // Re-read the save summary every time we come back to the menu.
                val info = remember(current) { SaveGame.info(saveFile) }
                MenuScreen(
                    onStart = {
                        error = null
                        screen = Screen.Solo(World(it))
                    },
                    save = info,
                    onContinue = {
                        try {
                            screen = Screen.Solo(SaveGame.load(saveFile))
                            error = null
                        } catch (e: Exception) {
                            saveFile.delete()
                            error = "Sauvegarde illisible, elle a été supprimée."
                        }
                    },
                    onMultiplayer = { screen = Screen.MultiplayerHome },
                    error = error,
                )
            }
            is Screen.Solo -> GameScreen(current.world, saveFile, onQuit = { screen = Screen.Menu })
            Screen.MultiplayerHome -> MultiplayerScreen(
                discovery,
                onBack = { screen = Screen.Menu },
                onHosted = { screen = Screen.HostLobby(it) },
                onJoined = { screen = Screen.GuestLobby(it) },
                pendingJoin = pendingJoin,
                onPendingJoinHandled = onPendingJoinHandled,
            )
            is Screen.HostLobby -> HostLobbyScreen(
                current.session, discovery,
                onStart = {
                    val world = current.session.start()
                    val controller = GameController(world, density).apply {
                        multiplayer = true
                        netHook = current.session
                    }
                    screen = Screen.MultiplayerGame(controller, current.session, null)
                },
                onCancel = {
                    current.session.close()
                    screen = Screen.MultiplayerHome
                },
            )
            is Screen.GuestLobby -> GuestLobbyScreen(
                current.session,
                onPlaying = {
                    val session = current.session
                    val controller = GameController(session.world!!, density).apply {
                        multiplayer = true
                        simulate = false
                        netHook = session
                        sendCommand = { session.send(it) }
                    }
                    screen = Screen.MultiplayerGame(controller, null, session)
                },
                onLeave = {
                    current.session.close()
                    screen = Screen.MultiplayerHome
                },
            )
            is Screen.MultiplayerGame -> MultiplayerGameScreen(current.controller, current.guest) {
                current.host?.close()
                current.guest?.close()
                screen = Screen.Menu
            }
        }
    }
}

/** A multiplayer game, plus a notice when the host disappears. */
@Composable
private fun MultiplayerGameScreen(controller: GameController, guest: GuestSession?, onQuit: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        GameScreen(controller, onQuit)
        val closed = controller.hud.let { guest?.state == GuestSession.State.CLOSED }
        if (closed && controller.world.winner == null) {
            Box(Modifier.fillMaxSize().background(Color(0xBB000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Column(
                    Modifier
                        .width(420.dp)
                        .background(PanelColor, RoundedCornerShape(12.dp))
                        .border(2.dp, BorderGold, RoundedCornerShape(12.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Partie interrompue", color = BorderGold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(guest?.closeReason ?: "", color = TextCream)
                    MenuButton("Menu principal", onClick = onQuit)
                }
            }
        }
    }
}

const val SAVE_FILE = "savegame.bin"

private val MAP_SIZES = listOf(56 to "Petite", 72 to "Moyenne", 96 to "Grande")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MenuScreen(
    onStart: (GameConfig) -> Unit,
    save: SaveInfo? = null,
    onContinue: () -> Unit = {},
    onMultiplayer: () -> Unit = {},
    error: String? = null,
) {
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
            // Scrollable so nothing gets cut on short screens or with large fonts; the Box keeps it centred.
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
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
                if (save != null) {
                    MenuButton("▶  Continuer") { onContinue() }
                    Text(
                        "${save.config.civ.label} contre ${save.config.opponents} IA (${save.config.difficulty.label.lowercase()}) • " +
                            "${formatTime(save.time)} • ${save.age.label}",
                        color = TextDim, fontSize = 12.sp,
                    )
                    Text(
                        "Sauvegardée le " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(save.savedAt)),
                        color = TextDim, fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                error?.let {
                    Text(it, color = Color(0xFFFF8070), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MenuButton(if (save != null) "⚔️  Nouvelle partie" else "⚔️  Jouer") {
                        onStart(GameConfig(civ, opponents, difficulty, mapSize, System.currentTimeMillis()))
                    }
                    MenuButton("👥  Multijoueur", onClick = onMultiplayer)
                    MenuButton(if (showHelp) "✖  Fermer l'aide" else "📖  Comment jouer") { showHelp = !showHelp }
                }
                if (save != null) Text("(Nouvelle partie remplacera la sauvegarde)", color = TextDim, fontSize = 11.sp)
            }
            }
            Spacer(Modifier.width(16.dp))
            Column(
                Modifier
                    .fillMaxWidth(0.5f)
                    .widthIn(max = 420.dp)
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
internal fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
