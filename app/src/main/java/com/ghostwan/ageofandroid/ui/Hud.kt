package com.ghostwan.ageofandroid.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostwan.ageofandroid.game.Age
import com.ghostwan.ageofandroid.game.Building
import com.ghostwan.ageofandroid.game.BuildingType
import com.ghostwan.ageofandroid.game.Entity
import com.ghostwan.ageofandroid.game.GameUnit
import com.ghostwan.ageofandroid.game.NodeKind
import com.ghostwan.ageofandroid.game.ResourceNode
import com.ghostwan.ageofandroid.game.ResourceType
import com.ghostwan.ageofandroid.game.Task
import com.ghostwan.ageofandroid.game.Terrain
import com.ghostwan.ageofandroid.game.UnitType
import kotlin.math.sqrt

val PanelColor = Color(0xE6261A10)
private val BOTTOM_BAR_HEIGHT = 136.dp
val BorderGold = Color(0xFFC9A24A)
val TextCream = Color(0xFFF3E6C4)
val TextDim = Color(0xFFB8A784)

@Composable
fun Hud(c: GameController, onMenu: () -> Unit) {
    c.hud // recompose on HUD ticks
    Box(Modifier.fillMaxSize()) {
        TopBar(c, onMenu, Modifier.align(Alignment.TopCenter))
        Toasts(c, Modifier.align(Alignment.TopCenter).padding(top = 46.dp))
        // The bottom bar can be folded away to free the map; it always shows while placing a building.
        val showBar = c.bottomBarVisible || c.placing != null
        if (showBar) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(BOTTOM_BAR_HEIGHT),
                verticalAlignment = Alignment.Bottom,
            ) {
                Minimap(c, Modifier.width(220.dp).fillMaxHeight())
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (c.placing != null) PlacementBar(c) else SelectionPanel(c)
                }
            }
        }
        if (c.placing == null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = if (showBar) BOTTOM_BAR_HEIGHT - 2.dp else 0.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(PanelColor)
                    .border(1.dp, BorderGold.copy(alpha = 0.6f), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .clickable { c.bottomBarVisible = !c.bottomBarVisible }
                    .padding(horizontal = 18.dp, vertical = 4.dp),
            ) {
                Text(
                    if (showBar) "▼" else "▲ ${c.selectedEntities().size.takeIf { it > 0 }?.let { "$it sélectionné(s)" } ?: "Carte et actions"}",
                    color = TextCream, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        c.lastError?.let {
            Text(
                it,
                color = Color(0xFFFFD0C0),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (c.bottomBarVisible || c.placing != null) BOTTOM_BAR_HEIGHT + 30.dp else 40.dp)
                    .background(Color(0xCC5A1010), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------- Top bar

@Composable
private fun TopBar(c: GameController, onMenu: () -> Unit, modifier: Modifier) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val p = c.world.human
    val counts = c.villagerCountsByResource()
    Row(
        modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(PanelColor)
            .border(1.dp, BorderGold.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ResourceType.entries.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.icon, fontSize = 16.sp)
                Spacer(Modifier.width(3.dp))
                Text("${p.res[t]}", color = TextCream, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(" (${counts[t.ordinal]})", color = TextDim, fontSize = 11.sp)
            }
        }
        val housed = p.pop >= p.popCap
        Text("👥 ${p.pop}/${p.popCap}", color = if (housed) Color(0xFFFF8070) else TextCream, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(p.age.label + if (p.researchingAge) " ⏳" else "", color = BorderGold, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(formatTime(c.world.time), color = TextDim, fontSize = 13.sp)
        val idle = c.idleVillagerCount()
        HudButton("💤👷 $idle", highlight = idle > 0) { c.selectIdleVillager() }
        HudButton("⚔️ ${c.armyCount()}") { c.selectArmy() }
        HudButton("🏛️") { c.selectTownCenter() }
        HudButton("x${if (c.speed % 1f == 0f) c.speed.toInt().toString() else c.speed.toString()}") {
            c.speed = when (c.speed) {
                1f -> 1.5f
                1.5f -> 2f
                2f -> 3f
                else -> 1f
            }
        }
        HudButton("☰") { onMenu() }
    }
}

@Composable
private fun HudButton(label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .height(32.dp)
            .widthIn(min = 38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) Color(0xFF6A4A1A) else Color(0xFF3A2A18))
            .border(1.dp, BorderGold.copy(alpha = if (highlight) 1f else 0.5f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TextCream, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

fun formatTime(t: Float): String {
    val s = t.toInt()
    return "%02d:%02d".format(s / 60, s % 60)
}

// ---------------------------------------------------------------------- Toasts

@Composable
private fun Toasts(c: GameController, modifier: Modifier) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        c.toasts.forEach { t ->
            Text(
                t.text,
                color = if (t.alert) Color(0xFFFFD6C8) else TextCream,
                fontSize = 14.sp,
                fontWeight = if (t.alert) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (t.alert) Color(0xD07A1A12) else Color(0xC0261A10))
                    .clickable(enabled = t.x >= 0f) { c.jumpTo(t.x, t.y) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------- Minimap

private class MinimapCache(n: Int) {
    val bitmap: Bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    val image = bitmap.asImageBitmap()
    val pixels = IntArray(n * n)
    var lastHud = -1
}

@Composable
private fun Minimap(c: GameController, modifier: Modifier) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val world = c.world
    val n = world.map.width
    val cache = remember(world) { MinimapCache(n) }
    val hud = c.hud
    fun toWorld(mx: Float, my: Float, w: Float): Offset {
        val a = w / (2f * n)
        val d = (mx - w / 2f) / a // x - y
        val s = 2f * my / a // x + y
        return Offset((d + s) / 2f, (s - d) / 2f)
    }
    Box(
        modifier
            .padding(4.dp)
            .background(PanelColor, RoundedCornerShape(8.dp))
            .border(1.dp, BorderGold.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(6.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(world) {
                    detectTapGestures { pos ->
                        val w = toWorld(pos.x, pos.y - (size.height - size.width / 2f) / 2f, size.width.toFloat())
                        c.minimapTap(w.x, w.y)
                    }
                }
                .pointerInput(world) {
                    detectDragGestures { change, _ ->
                        val pos = change.position
                        val w = toWorld(pos.x, pos.y - (size.height - size.width / 2f) / 2f, size.width.toFloat())
                        c.minimapTap(w.x, w.y)
                    }
                },
        ) {
            if (cache.lastHud != hud) {
                cache.lastHud = hud
                fillMinimap(c, cache.pixels)
                cache.bitmap.setPixels(cache.pixels, 0, n, 0, 0, n, n)
            }
            val w = size.width
            val top = (size.height - w / 2f) / 2f
            val k = w / (2f * n) * sqrt(2f)
            withTransform({
                translate(w / 2f, top)
                scale(1f, 0.5f, pivot = Offset.Zero)
                rotate(45f, pivot = Offset.Zero)
                scale(k, k, pivot = Offset.Zero)
            }) {
                drawImage(cache.image, IntOffset.Zero, IntSize(n, n), IntOffset.Zero, IntSize(n, n), filterQuality = FilterQuality.None)
            }
            // Camera viewport
            val a = w / (2f * n)
            fun mm(wx: Float, wy: Float) = Offset((wx - wy) * a + w / 2f, (wx + wy) * a / 2f + top)
            val corners = listOf(
                c.camera.toWorld(0f, 0f), c.camera.toWorld(c.viewW, 0f),
                c.camera.toWorld(c.viewW, c.viewH), c.camera.toWorld(0f, c.viewH),
            ).map { mm(it.x, it.y) }
            val path = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                corners.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, Color.White, style = Stroke(1.5f))
        }
    }
}

private fun fillMinimap(c: GameController, px: IntArray) {
    val world = c.world
    val map = world.map
    val n = map.width
    for (i in 0 until n * n) {
        px[i] = when (map.terrain[i]) {
            Terrain.GRASS -> 0xFF4E7A2A.toInt()
            Terrain.DIRT -> 0xFF8A6C40.toInt()
            Terrain.SAND -> 0xFFC8B07A.toInt()
            Terrain.WATER -> 0xFF2A5F94.toInt()
        }
    }
    for (nd in world.nodes) {
        if (!nd.alive) continue
        px[map.idx(nd.tx, nd.ty)] = when (nd.kind) {
            NodeKind.TREE -> 0xFF1F4A18.toInt()
            NodeKind.BERRIES -> 0xFFC04050.toInt()
            NodeKind.GOLD -> 0xFFF0C830.toInt()
            NodeKind.STONE -> 0xFFB0B0B0.toInt()
        }
    }
    for (b in world.buildings) {
        if (!b.alive || !world.canSee(b)) continue
        val col = world.players[b.owner].color.toInt()
        for (y in b.ty until b.ty + b.size) for (x in b.tx until b.tx + b.size) if (map.inBounds(x, y)) px[map.idx(x, y)] = col
    }
    for (u in world.units) {
        if (!u.alive || !world.canSee(u)) continue
        val x = u.x.toInt().coerceIn(0, n - 1)
        val y = u.y.toInt().coerceIn(0, n - 1)
        px[map.idx(x, y)] = GameRenderer.lighten(Color(world.players[u.owner].color), 0.35f).let {
            (0xFF shl 24) or ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt()
        }
    }
    for (i in 0 until n * n) {
        if (!world.explored[i]) {
            px[i] = 0xFF0C0A08.toInt()
        } else if (!world.visible[i]) {
            val p = px[i]
            val r = (p shr 16 and 0xff) * 6 / 10
            val g = (p shr 8 and 0xff) * 6 / 10
            val b = (p and 0xff) * 6 / 10
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

// ---------------------------------------------------------------------- Placement

@Composable
private fun PlacementBar(c: GameController) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val type = c.placing ?: return
    Row(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .background(PanelColor, RoundedCornerShape(8.dp))
            .border(1.dp, BorderGold.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(type.icon, fontSize = 34.sp)
        Column(Modifier.weight(1f)) {
            Text("Placer : ${type.label}", color = TextCream, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Touchez la carte pour déplacer le bâtiment, puis validez.", color = TextDim, fontSize = 12.sp)
            Text(c.world.human.buildingCost(type).toString(), color = TextCream, fontSize = 13.sp)
        }
        ActionButton("✔", "Placer", enabled = c.ghostValid()) { c.confirmPlacement() }
        if (type == BuildingType.HOUSE || type == BuildingType.FARM) {
            ActionButton("✔+", "Encore", enabled = c.ghostValid()) { c.confirmPlacement(keepPlacing = true) }
        }
        ActionButton("✖", "Annuler") { c.cancelPlacement() }
    }
}

// ---------------------------------------------------------------------- Selection panel

@Composable
private fun SelectionPanel(c: GameController) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val entities = c.selectedEntities()
    Row(
        Modifier
            .fillMaxSize()
            .padding(4.dp)
            .background(PanelColor, RoundedCornerShape(8.dp))
            .border(1.dp, BorderGold.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            entities.isEmpty() -> Text(
                "Touchez une unité pour la sélectionner • Appui long puis glisser pour une sélection de groupe • " +
                    "Deux doigts pour zoomer • Touchez la carte pour donner un ordre",
                color = TextDim,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            entities.size == 1 -> SingleSelection(c, entities.first())
            else -> MultiSelection(c, entities.filterIsInstance<GameUnit>())
        }
    }
}

@Composable
private fun EntityHeader(icon: String, title: String, subtitle: String, hp: Float?, maxHp: Float?, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.width(200.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(4.dp))
            GameIcon(icon, 20.sp)
            Spacer(Modifier.width(4.dp))
            Text(title, color = TextCream, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hp != null && maxHp != null) {
            Spacer(Modifier.height(3.dp))
            // Hit points are written inside the bar so they never get pushed out of the panel.
            Box(Modifier.fillMaxWidth().height(16.dp).background(Color(0xFF3A1010), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.align(Alignment.CenterStart).fillMaxWidth((hp / maxHp).coerceIn(0f, 1f)).fillMaxHeight()
                        .background(Color(0xFF3E9E3E), RoundedCornerShape(3.dp)),
                )
                Text("${hp.toInt().coerceAtLeast(0)} / ${maxHp.toInt()} PV", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(2.dp))
        }
        Text(subtitle, color = TextDim, fontSize = 11.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun SingleSelection(c: GameController, e: Entity) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val world = c.world
    val ownerColor = if (e.owner >= 0) Color(world.players[e.owner].color) else Color(0xFFE0C050)
    when (e) {
        is GameUnit -> {
            val p = world.players[e.owner]
            val stats = buildString {
                append("⚔️${world.attackOf(p, e.type)}  🛡️${world.meleeArmorOf(p, e.type)}/${world.pierceArmorOf(p, e.type)}")
                val r = world.rangeOf(p, e.type)
                if (r > 0f) append("  🎯${r.toInt()}")
                append("\n").append(taskLabel(c, e))
            }
            EntityHeader(e.type.icon, e.type.label + if (e.owner != 0) " (${p.name})" else "", stats, e.hp, e.maxHp, ownerColor)
            if (e.owner == 0) UnitActions(c, listOf(e))
        }
        is Building -> {
            val p = world.players[e.owner]
            val subtitle = when {
                !e.complete -> "Construction : ${(e.progress * 100).toInt()}%"
                e.type == BuildingType.FARM -> "Nourriture restante : ${e.food.toInt()}"
                e.type == BuildingType.WONDER && e.wonderTimer > 0f -> "Victoire dans ${formatTime(e.wonderTimer)}"
                e.type.trains.isNotEmpty() && e.owner == 0 -> e.type.description + " Touchez la carte pour placer le point de ralliement."
                else -> e.type.description
            }
            EntityHeader(e.type.icon, e.type.label + if (e.owner != 0) " (${p.name})" else "", subtitle, e.hp, e.maxHp, ownerColor)
            if (e.owner == 0) BuildingActions(c, e)
        }
        is ResourceNode -> {
            EntityHeader(
                when (e.kind) {
                    NodeKind.TREE -> "🌳"
                    NodeKind.BERRIES -> "🍇"
                    NodeKind.GOLD -> "💰"
                    NodeKind.STONE -> "⛰️"
                },
                e.kind.label,
                "${e.kind.resource.icon} ${e.amount.toInt()} restant(s)",
                null, null, ownerColor,
            )
        }
    }
}

private fun taskLabel(c: GameController, u: GameUnit): String {
    val target = c.world.entity(u.targetId)
    val carry = if (u.carryAmount >= 1f) " — porte ${u.carryAmount.toInt()} ${u.carryType?.icon ?: ""}" else ""
    return when (u.task) {
        Task.IDLE -> "Inactif$carry"
        Task.MOVE -> "Se déplace$carry"
        Task.GATHER -> when (target) {
            is ResourceNode -> "Récolte : ${target.kind.resource.label.lowercase()}$carry"
            is Building -> "Cultive la ferme$carry"
            else -> "Récolte$carry"
        }
        Task.RETURN -> "Dépose les ressources$carry"
        Task.BUILD -> "Construit : ${(target as? Building)?.type?.label ?: ""}"
        Task.ATTACK -> "Attaque"
    }
}

@Composable
private fun MultiSelection(c: GameController, units: List<GameUnit>) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val groups = units.groupBy { it.type }
    Column(Modifier.width(190.dp)) {
        Text("${units.size} unités", color = TextCream, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(groups.entries.joinToString("  ") { "${it.key.icon}×${it.value.size}" }, color = TextCream, fontSize = 15.sp)
        val hp = units.sumOf { it.hp.toDouble() }
        val max = units.sumOf { it.maxHp.toDouble() }
        Text("PV : ${(hp / max * 100).toInt()}%", color = TextDim, fontSize = 12.sp)
    }
    UnitActions(c, units.filter { it.owner == 0 })
}

@Composable
private fun UnitActions(c: GameController, units: List<GameUnit>) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val p = c.world.human
    val hasVillager = units.any { it.type == UnitType.VILLAGER }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        item { ActionButton("✋", "Stop") { c.stop() } }
        if (hasVillager) {
            item { SectionLabel("Économie") }
            ECONOMY.forEach { t -> item { BuildButton(c, t) } }
            item { SectionLabel("Militaire") }
            MILITARY.forEach { t -> item { BuildButton(c, t) } }
            if (p.age >= Age.CASTLE) item { BuildButton(c, BuildingType.TOWN_CENTER) }
        }
        item { ActionButton("🗑️", "Supprimer") { c.deleteSelected() } }
    }
}

private val ECONOMY = listOf(BuildingType.HOUSE, BuildingType.FARM, BuildingType.MILL, BuildingType.LUMBER_CAMP, BuildingType.MINING_CAMP)
private val MILITARY = listOf(
    BuildingType.BARRACKS, BuildingType.ARCHERY_RANGE, BuildingType.STABLE, BuildingType.BLACKSMITH,
    BuildingType.WATCH_TOWER, BuildingType.SIEGE_WORKSHOP, BuildingType.WONDER,
)

@Composable
private fun SectionLabel(text: String) {
    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = BorderGold, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
        Box(Modifier.padding(top = 4.dp).width(2.dp).height(70.dp).background(BorderGold.copy(alpha = 0.5f)))
    }
}

@Composable
private fun BuildButton(c: GameController, t: BuildingType) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val p = c.world.human
    val locked = p.age < t.minAge
    val cost = p.buildingCost(t)
    ActionButton(
        t.icon,
        if (locked) "🔒 ${t.minAge.label.removePrefix("Âge ").removePrefix("des ")}" else t.label,
        cost = cost.toString(),
        enabled = !locked,
        affordable = p.res.canAfford(cost),
    ) { c.startPlacement(t) }
}

@Composable
private fun BuildingActions(c: GameController, b: Building) {
    c.hud // read HUD ticks: with strong skipping this composable would otherwise never refresh
    val p = c.world.human
    if (!b.complete) {
        LazyRow { item { ActionButton("🗑️", "Annuler") { c.deleteSelected() } } }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            b.type.trains.forEach { u ->
                item {
                    val locked = p.age < u.minAge
                    ActionButton(
                        u.icon,
                        if (locked) "🔒 ${u.label}" else u.label,
                        cost = u.cost.toString(),
                        time = u.trainTime,
                        enabled = !locked,
                        affordable = p.res.canAfford(u.cost),
                    ) { c.train(u) }
                }
            }
            if (b.type == BuildingType.TOWN_CENTER) {
                p.age.next?.let { next ->
                    item {
                        ActionButton(
                            "⏫", next.label, cost = next.cost.toString(), time = next.researchTime,
                            enabled = !p.researchingAge, affordable = p.res.canAfford(next.cost),
                        ) { c.advanceAge() }
                    }
                }
            }
            b.type.techs.filter { !p.has(it) }.forEach { t ->
                item {
                    val locked = p.age < t.minAge
                    ActionButton(
                        t.icon,
                        if (locked) "🔒 ${t.label}" else t.label,
                        cost = t.cost.toString(),
                        time = t.time,
                        enabled = !locked && t !in p.researching,
                        affordable = p.res.canAfford(t.cost),
                    ) { c.research(t) }
                }
            }
            item { ActionButton("🗑️", "Détruire") { c.deleteSelected() } }
        }
        if (b.queue.isNotEmpty()) {
            Column(Modifier.padding(start = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val current = b.queue.first()
                val remaining = (current.time - b.queueProgress).coerceAtLeast(0f)
                val housed = current.unit != null && p.pop >= p.popCap
                Text(
                    if (housed) "En attente : maisons !" else "${current.label} : ${remaining.toInt() + 1}s",
                    color = if (housed) Color(0xFFFF8070) else TextCream, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                    b.queue.forEachIndexed { i, item ->
                        val big = i == 0
                        Box(
                            Modifier
                                .size(if (big) 48.dp else 34.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF3A2A18))
                                .border(1.dp, BorderGold.copy(alpha = if (big) 1f else 0.6f), RoundedCornerShape(4.dp))
                                .clickable { c.cancelQueue(i) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (big) {
                                Box(
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .fillMaxWidth((b.queueProgress / item.time).coerceIn(0f, 1f))
                                        .fillMaxHeight()
                                        .background(Color(0x664CC04C)),
                                )
                            }
                            GameIcon(item.icon, if (big) 24.sp else 17.sp)
                        }
                    }
                }
                Text("Toucher = annuler", color = TextDim, fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun ActionButton(
    icon: String,
    label: String,
    cost: String? = null,
    time: Float? = null,
    enabled: Boolean = true,
    affordable: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(76.dp)
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) Color(0xFF3E2C18) else Color(0xFF2A2018))
            .border(1.dp, if (enabled && affordable) BorderGold else BorderGold.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GameIcon(icon, 26.sp, dim = !enabled)
        Text(
            label, color = if (enabled) TextCream else TextDim, fontSize = 10.sp, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp,
        )
        if (cost != null) {
            Text(
                cost, color = if (affordable) TextDim else Color(0xFFFF7060), fontSize = 9.sp, textAlign = TextAlign.Center,
                maxLines = 2, lineHeight = 10.sp,
            )
        }
        if (time != null) Text("⏱ ${time.toInt()}s", color = TextDim, fontSize = 9.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------------- Menus

@Composable
fun PauseMenu(c: GameController, onResume: () -> Unit, onSave: (() -> Boolean)?, onQuit: () -> Unit) {
    var saved by remember { mutableStateOf<Boolean?>(null) }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(520.dp)
                .background(PanelColor, RoundedCornerShape(12.dp))
                .border(2.dp, BorderGold, RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Pause", color = BorderGold, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (onSave != null) {
                Text(
                    when (saved) {
                        true -> "✅ Partie sauvegardée"
                        false -> "⚠️ Échec de la sauvegarde"
                        null -> "La partie est sauvegardée automatiquement à la pause et en quittant l'application."
                    },
                    color = if (saved == false) Color(0xFFFF8070) else TextDim, fontSize = 12.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuButton("▶ Reprendre", Modifier.weight(1f), onResume)
                if (onSave != null) MenuButton("💾 Sauvegarder", Modifier.weight(1f)) { saved = onSave() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MenuButton(if (c.world.revealAll) "🌫️ Brouillard" else "👁️ Révéler la carte", Modifier.weight(1f)) {
                    c.world.revealAll = !c.world.revealAll
                    c.world.updateFog()
                    c.invalidateHud()
                }
                MenuButton(if (onSave != null) "🚪 Sauver et quitter" else "🚪 Quitter", Modifier.weight(1f), onQuit)
            }
            Text(HELP_TEXT, color = TextCream, fontSize = 13.sp)
        }
    }
}

const val HELP_TEXT = """Objectif : détruisez tous les bâtiments et unités ennemis, ou construisez une Merveille et gardez-la 3 minutes.

• Toucher : sélectionner une unité / un bâtiment.
• Appui long + glisser : sélection de groupe.
• Glisser : déplacer la caméra. Pincer : zoomer.
• Unités sélectionnées + toucher la carte : ordre contextuel (déplacer, récolter, construire, attaquer).
• Bâtiment sélectionné + toucher la carte : point de ralliement.
• 💤👷 : villageois inactif suivant. ⚔️ : toute l'armée. 🏛️ : forum.
• Les villageois déposent les ressources au forum ou au camp le plus proche : construisez des camps près des forêts et des mines !
• Construisez des maisons pour augmenter la population, et passez les âges au forum pour débloquer de nouvelles unités."""

@Composable
fun MenuButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF5A3E1C))
            .border(1.dp, BorderGold, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = TextCream, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
    }
}

@Composable
fun EndDialog(c: GameController, winner: Int, onQuit: () -> Unit) {
    val p = c.world.human
    val won = winner == 0
    Box(Modifier.fillMaxSize().background(Color(0xBB000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(440.dp)
                .background(PanelColor, RoundedCornerShape(12.dp))
                .border(2.dp, BorderGold, RoundedCornerShape(12.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (won) "🏆 Victoire !" else "💀 Défaite", color = if (won) BorderGold else Color(0xFFFF7060), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text("Durée : ${formatTime(c.world.time)} — ${p.age.label}", color = TextCream)
            Text("Unités tuées : ${p.unitsKilled} • Unités perdues : ${p.unitsLost} • Bâtiments rasés : ${p.buildingsRazed}", color = TextCream, fontSize = 13.sp)
            Text(
                "Ressources récoltées : " + ResourceType.entries.joinToString("  ") { "${it.icon}${p.gathered[it.ordinal].toInt()}" },
                color = TextCream, fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            MenuButton("Menu principal", onClick = onQuit)
        }
    }
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextCream,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) = androidx.compose.material3.Text(
    text, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign,
    maxLines = maxLines, overflow = overflow, lineHeight = lineHeight,
)
