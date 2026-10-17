package com.ghostwan.ageofandroid.ui

import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostwan.ageofandroid.game.Civ
import com.ghostwan.ageofandroid.game.Difficulty
import com.ghostwan.ageofandroid.game.Player
import com.ghostwan.ageofandroid.net.DEFAULT_PORT
import com.ghostwan.ageofandroid.net.FoundGame
import com.ghostwan.ageofandroid.net.GameMode
import com.ghostwan.ageofandroid.net.GuestSession
import com.ghostwan.ageofandroid.net.HostSession
import com.ghostwan.ageofandroid.net.JoinLink
import com.ghostwan.ageofandroid.net.LanDiscovery
import com.ghostwan.ageofandroid.net.Lobby
import com.ghostwan.ageofandroid.net.MAX_SEATS
import com.ghostwan.ageofandroid.net.SeatKind
import com.ghostwan.ageofandroid.net.TcpLink
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "multiplayer"

private val MP_MAP_SIZES = listOf(56 to "Petite", 72 to "Moyenne", 96 to "Grande")

/** Entry screen: pick a name and civilisation, then host a game or join one on the local network. */
@Composable
fun MultiplayerScreen(
    discovery: LanDiscovery?,
    onBack: () -> Unit,
    onHosted: (HostSession) -> Unit,
    onJoined: (GuestSession) -> Unit,
    /** Link from a QR code scanned with the phone's camera app, joined right away. */
    pendingJoin: JoinLink? = null,
    onPendingJoinHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("name", null) ?: Build.MODEL.take(20)) }
    var civ by remember { mutableStateOf(Civ.entries.getOrElse(prefs.getInt("civ", 0)) { Civ.FRANKS }) }
    var address by remember { mutableStateOf(prefs.getString("lastHost", "") ?: "") }
    var games by remember { mutableStateOf(emptyList<FoundGame>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)
    DisposableEffect(discovery) {
        discovery?.discover { games = it }
        onDispose { discovery?.stopDiscovery() }
    }

    fun saveProfile() = prefs.edit().putString("name", name.trim()).putInt("civ", civ.ordinal).apply()

    /** Tries each address of the host in turn (a phone can have several, e.g. Wi-Fi and hotspot). */
    fun join(target: JoinLink) {
        if (busy) return
        saveProfile()
        busy = true
        error = null
        scope.launch {
            var link: TcpLink? = null
            for (host in target.hosts) {
                link = withContext(Dispatchers.IO) {
                    try {
                        TcpLink.connect(host, target.port)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (link != null) {
                    prefs.edit().putString("lastHost", if (target.port == DEFAULT_PORT) host else "$host:${target.port}").apply()
                    break
                }
            }
            busy = false
            if (link != null) {
                onJoined(GuestSession(link, name.trim().ifBlank { "Joueur" }, civ))
            } else {
                val who = target.name.ifEmpty { target.hosts.first() }
                error = "Impossible de rejoindre $who : vérifiez que les deux téléphones sont sur le même Wi-Fi."
            }
        }
    }

    fun scan() {
        error = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { code ->
                val target = code.rawValue?.let { JoinLink.parse(it) }
                if (target != null) join(target) else error = "Ce QR code n'est pas une partie Age of Android."
            }
            .addOnFailureListener { error = "Scanner indisponible sur ce téléphone : saisissez l'adresse de l'hôte." }
    }

    LaunchedEffect(pendingJoin) {
        if (pendingJoin != null) {
            join(pendingJoin)
            onPendingJoinHandled()
        }
    }

    MpBackground {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Multijoueur", color = BorderGold, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Sur le même réseau Wi-Fi", color = TextDim, fontSize = 13.sp)
            MpTextField(name, { name = it.take(20) }, "Votre nom")
            Text("Votre civilisation", color = BorderGold, fontWeight = FontWeight.Bold)
            CivChips(civ) { civ = it }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuButton("👑  Créer une partie") {
                    saveProfile()
                    try {
                        onHosted(createHost(name.trim().ifBlank { "Hôte" }, civ))
                    } catch (e: Exception) {
                        error = "Impossible de créer la partie : ${e.message}"
                    }
                }
                MenuButton("←  Retour", onClick = onBack)
            }
            error?.let { Text(it, color = Color(0xFFFF8070), fontSize = 13.sp) }
        }
        Spacer(Modifier.width(16.dp))
        MpPanel(Modifier.weight(1f)) {
            Text("Rejoindre une partie", color = BorderGold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            MenuButton(if (busy) "⏳  Connexion…" else "📷  Scanner le QR code de l'hôte") { if (!busy) scan() }
            if (games.isEmpty()) {
                Text(
                    if (discovery != null) "🔎 Recherche des parties sur le Wi-Fi…" else "Recherche indisponible : saisissez l'adresse de l'hôte.",
                    color = TextDim, fontSize = 13.sp,
                )
            }
            games.forEach { g ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF3A2A18))
                        .border(1.dp, BorderGold, RoundedCornerShape(8.dp))
                        .clickable(enabled = !busy) { join(JoinLink(listOf(g.host), g.port, g.name)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.name, fontWeight = FontWeight.Bold)
                        Text(g.host, color = TextDim, fontSize = 12.sp)
                    }
                    Text("Rejoindre ▶", color = BorderGold, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Ou saisissez l'adresse affichée chez l'hôte :", color = TextDim, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    MpTextField(address, { address = it.trim() }, "Adresse IP (ex. 192.168.1.20)", KeyboardType.Uri)
                }
                MenuButton(if (busy) "…" else "Rejoindre") {
                    JoinLink.parse(address)?.let { join(it) }
                }
            }
        }
    }
}

private fun createHost(name: String, civ: Civ): HostSession = try {
    HostSession(name, civ, DEFAULT_PORT)
} catch (e: java.io.IOException) {
    // Port taken (e.g. a previous game not fully closed): any free port, shown in the lobby.
    HostSession(name, civ, 0)
}


/** The host's lobby: game settings, seats, then "Lancer". */
@Composable
fun HostLobbyScreen(
    session: HostSession,
    discovery: LanDiscovery?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    localAddresses: () -> List<String> = { LanDiscovery.localAddresses() },
) {
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(session) {
        while (true) {
            session.poll()
            version = session.version
            delay(50)
        }
    }
    DisposableEffect(session) {
        discovery?.advertise("Partie de ${session.lobby.hostName}", session.port)
        onDispose { discovery?.stopAdvertising() }
    }
    BackHandler(onBack = onCancel)
    version // read to refresh on lobby changes
    val lobby = session.lobby
    val addresses = remember { localAddresses() }

    MpBackground {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Votre partie", color = BorderGold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (addresses.isEmpty()) {
                Text("⚠️ Aucun réseau Wi-Fi détecté : connectez-vous au même Wi-Fi que les autres joueurs.", color = Color(0xFFFF8070), fontSize = 12.sp)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrCode(JoinLink(addresses, session.port, lobby.hostName).toString(), 104.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Pour rejoindre, les autres joueurs :", color = TextCream, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("• scannent ce QR code (appareil photo ou Multijoueur › Scanner)", color = TextDim, fontSize = 12.sp)
                        Text("• ou trouvent la partie dans Multijoueur › Rejoindre", color = TextDim, fontSize = 12.sp)
                        Text(
                            "• ou saisissent : ${addresses.first()}" + if (session.port != DEFAULT_PORT) ":${session.port}" else "",
                            color = TextDim, fontSize = 12.sp,
                        )
                    }
                }
            }
            Text("Mode", color = BorderGold, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GameMode.entries.forEach { m -> Chip(m.label, lobby.mode == m, Modifier.weight(1f)) { session.setMode(m) } }
            }
            Text(lobby.mode.description, color = TextDim, fontSize = 12.sp)
            Text("Taille de la carte", color = BorderGold, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MP_MAP_SIZES.forEach { (s, label) -> Chip(label, lobby.mapSize == s, Modifier.weight(1f)) { session.setMapSize(s) } }
            }
            if (lobby.ais > 0) {
                Text("Difficulté des IA", color = BorderGold, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Difficulty.entries.forEach { d -> Chip(d.label, lobby.difficulty == d, Modifier.weight(1f)) { session.setDifficulty(d) } }
                }
            }
            Text("Votre civilisation", color = BorderGold, fontWeight = FontWeight.Bold)
            CivChips(lobby.seats.first { it.kind == SeatKind.HOST }.civ) { session.setHostCiv(it) }
        }
        Spacer(Modifier.width(16.dp))
        MpPanel(Modifier.weight(1f)) {
            Text("Joueurs (${lobby.seats.size}/$MAX_SEATS)", color = BorderGold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            SeatList(lobby, me = 0, onRemove = { session.removeSeat(it) })
            if (lobby.seats.size < MAX_SEATS) {
                Text(
                    "＋ Ajouter une IA", color = BorderGold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { session.addAi() }.padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            lobby.blocker?.let { Text(it, color = TextDim, fontSize = 13.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (lobby.blocker == null) MenuButton("⚔️  Lancer la partie", onClick = onStart)
                MenuButton("✖  Annuler", onClick = onCancel)
            }
        }
    }
}

/** A guest's view of the lobby while waiting for the host. */
@Composable
fun GuestLobbyScreen(session: GuestSession, onPlaying: () -> Unit, onLeave: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(session) {
        while (true) {
            session.poll()
            version = session.version
            if (session.state == GuestSession.State.PLAYING) {
                onPlaying()
                break
            }
            delay(50)
        }
    }
    BackHandler(onBack = onLeave)
    version // read to refresh on lobby changes
    val lobby = session.lobby
    val myIndex = lobby?.seats?.indexOfFirst { it.kind == SeatKind.GUEST && it.clientId == session.clientId } ?: -1

    MpBackground {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                session.state == GuestSession.State.CLOSED -> {
                    Text("Partie fermée", color = BorderGold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(session.closeReason ?: "", color = Color(0xFFFF8070))
                    MenuButton("Retour", onClick = onLeave)
                }
                lobby == null -> {
                    Text("Connexion…", color = BorderGold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    MenuButton("✖  Annuler", onClick = onLeave)
                }
                else -> {
                    Text("Partie de ${lobby.hostName}", color = BorderGold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "${lobby.mode.label} • carte ${MP_MAP_SIZES.firstOrNull { it.first == lobby.mapSize }?.second?.lowercase() ?: lobby.mapSize}" +
                            if (lobby.ais > 0) " • IA ${lobby.difficulty.label.lowercase()}" else "",
                        color = TextDim, fontSize = 13.sp,
                    )
                    if (myIndex >= 0) {
                        Text("Votre civilisation", color = BorderGold, fontWeight = FontWeight.Bold)
                        CivChips(lobby.seats[myIndex].civ) { session.setCiv(it) }
                    }
                    Text("⏳ En attente du lancement par l'hôte…", color = TextCream)
                    MenuButton("🚪  Quitter", onClick = onLeave)
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        if (lobby != null) {
            MpPanel(Modifier.weight(1f)) {
                Text("Joueurs (${lobby.seats.size}/$MAX_SEATS)", color = BorderGold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                SeatList(lobby, me = myIndex, onRemove = null)
            }
        }
    }
}

@Composable
private fun SeatList(lobby: Lobby, me: Int, onRemove: ((Int) -> Unit)?) {
    lobby.seats.forEachIndexed { i, seat ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (i == me) Color(0xFF4A3618) else Color(0xFF2E2216))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Color(Player.COLORS[i])))
            Column(Modifier.weight(1f)) {
                Text(
                    when (seat.kind) {
                        SeatKind.HOST -> "👑 ${seat.name}"
                        SeatKind.GUEST -> "📱 ${seat.name}"
                        SeatKind.AI -> "🤖 ${seat.name}"
                    } + if (i == me) " (vous)" else "",
                    fontWeight = FontWeight.Bold, maxLines = 1,
                )
                Text(
                    seat.civ.label + "  •  " + when (lobby.mode) {
                        GameMode.COOP -> if (seat.kind == SeatKind.AI) "Équipe IA" else "Équipe des joueurs"
                        GameMode.FFA -> "Seul contre tous"
                    },
                    color = TextDim, fontSize = 12.sp, maxLines = 1,
                )
            }
            if (onRemove != null && seat.kind != SeatKind.HOST) {
                Text("✖", color = TextDim, fontSize = 16.sp, modifier = Modifier.clickable { onRemove(i) }.padding(6.dp))
            }
        }
    }
}

@Composable
private fun CivChips(selected: Civ, onSelect: (Civ) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Civ.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c -> Chip(c.label, selected == c, Modifier.weight(1f)) { onSelect(c) } }
            }
        }
        Text("Bonus : ${selected.bonus}", color = TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun MpTextField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { androidx.compose.material3.Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextCream, unfocusedTextColor = TextCream,
            focusedBorderColor = BorderGold, unfocusedBorderColor = BorderGold.copy(alpha = 0.5f),
            focusedLabelColor = BorderGold, unfocusedLabelColor = TextDim, cursorColor = BorderGold,
        ),
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
    )
}

@Composable
private fun MpBackground(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A5A2A), Color(0xFF1B140C)))),
    ) {
        Row(Modifier.fillMaxSize().padding(24.dp), content = content)
    }
}

@Composable
private fun MpPanel(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxHeight()
            .background(PanelColor, RoundedCornerShape(12.dp))
            .border(2.dp, BorderGold, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
