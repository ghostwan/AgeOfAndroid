package com.ghostwan.ageofandroid.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ghostwan.ageofandroid.game.Building
import com.ghostwan.ageofandroid.game.BuildingType
import com.ghostwan.ageofandroid.game.Category
import com.ghostwan.ageofandroid.game.Entity
import com.ghostwan.ageofandroid.game.GameUnit
import com.ghostwan.ageofandroid.game.NodeKind
import com.ghostwan.ageofandroid.game.ResourceNode
import com.ghostwan.ageofandroid.game.ResourceType
import com.ghostwan.ageofandroid.game.Task
import com.ghostwan.ageofandroid.game.Terrain
import com.ghostwan.ageofandroid.game.UnitType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class GameRenderer(private val c: GameController) {
    private val world get() = c.world
    private val cam get() = c.camera
    private val n = c.world.map.width

    private val terrainImage: ImageBitmap by lazy { buildTerrain() }
    private val fogBitmap: Bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    private val fogImage = fogBitmap.asImageBitmap()
    private val fogPixels = IntArray(n * n)
    private var fogFrame = -100L

    private val path = Path()
    private val drawList = ArrayList<Entity>(512)

    // ------------------------------------------------------------------ Terrain & fog textures

    private fun buildTerrain(): ImageBitmap {
        val map = world.map
        val bmp = Bitmap.createBitmap(n * TEX, n * TEX, Bitmap.Config.ARGB_8888)
        val px = IntArray(n * TEX * n * TEX)
        val rnd = Random(world.config.seed)
        for (ty in 0 until n) for (tx in 0 until n) {
            val t = map.terrainAt(tx, ty)
            val base = when (t) {
                Terrain.GRASS -> 0x5E8C31
                Terrain.DIRT -> 0x9A7B4C
                Terrain.SAND -> 0xD6C089
                Terrain.WATER -> 0x2E6EA6
            }
            val tileShade = (map.shade[map.idx(tx, ty)] - 4) * 0.012f
            for (y in 0 until TEX) for (x in 0 until TEX) {
                val noise = (rnd.nextFloat() - 0.5f) * if (t == Terrain.WATER) 0.06f else 0.16f
                val f = 1f + noise + tileShade
                val r = ((base shr 16 and 0xff) * f).toInt().coerceIn(0, 255)
                val g = ((base shr 8 and 0xff) * f).toInt().coerceIn(0, 255)
                val b = ((base and 0xff) * f).toInt().coerceIn(0, 255)
                px[(ty * TEX + y) * n * TEX + tx * TEX + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bmp.setPixels(px, 0, n * TEX, 0, 0, n * TEX, n * TEX)
        return bmp.asImageBitmap()
    }

    private fun updateFog() {
        if (c.frame - fogFrame < 6) return
        fogFrame = c.frame
        val vis = world.visible
        val exp = world.explored
        for (i in 0 until n * n) {
            fogPixels[i] = when {
                vis[i] -> 0x00000000
                exp[i] -> 0x8C000000.toInt()
                else -> 0xFF0C0A08.toInt()
            }
        }
        fogBitmap.setPixels(fogPixels, 0, n, 0, 0, n, n)
    }

    private fun DrawScope.drawMapImage(image: ImageBitmap, srcSize: Int) {
        val k = cam.tw / sqrt(2f)
        withTransform({
            translate(cam.offsetX, cam.offsetY)
            scale(1f, 0.5f, pivot = Offset.Zero)
            rotate(45f, pivot = Offset.Zero)
            scale(k / (srcSize / n), k / (srcSize / n), pivot = Offset.Zero)
        }) {
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(srcSize, srcSize),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(srcSize, srcSize),
                filterQuality = FilterQuality.Low,
            )
        }
    }

    // ------------------------------------------------------------------ Frame

    fun draw(scope: DrawScope) = with(scope) {
        drawRect(Color(0xFF0C0A08))
        drawMapImage(terrainImage, n * TEX)
        val time = world.time

        // Collect what's on screen.
        drawList.clear()
        val margin = cam.tw * 2.5f
        fun onScreen(x: Float, y: Float): Boolean {
            val sx = cam.sx(x, y)
            val sy = cam.sy(x, y)
            return sx > -margin && sx < size.width + margin && sy > -margin && sy < size.height + margin * 1.5f
        }
        for (b in world.buildings) if (b.alive && onScreen(b.x, b.y) && world.canSee(b)) drawList += b
        for (nd in world.nodes) if (nd.alive && onScreen(nd.x, nd.y) && world.canSee(nd)) drawList += nd
        for (u in world.units) if (u.alive && onScreen(u.x, u.y) && world.canSee(u)) drawList += u

        // Flat things first (farms, foundations), then depth-sorted.
        for (e in drawList) if (e is Building && (e.type == BuildingType.FARM)) drawFarm(e)
        for (e in drawList) if (e is Building && c.isSelected(e.id)) drawFootprint(e.tx, e.ty, e.size, Color.White, 2f)
        for (e in drawList) if (e is GameUnit && c.isSelected(e.id)) drawSelectionRing(e)
        drawList.sortBy { depth(it) }
        for (e in drawList) {
            when (e) {
                is ResourceNode -> drawNode(e)
                is Building -> if (e.type != BuildingType.FARM) drawBuilding(e, time)
                is GameUnit -> drawUnit(e, time)
            }
        }
        // Projectiles
        for (p in world.projectiles) {
            if (!p.alive || !world.isVisible(p.x, p.y)) continue
            val arc = sin(p.progress * PI).toFloat() * cam.tw * 0.35f
            val sx = cam.sx(p.x, p.y)
            val sy = cam.sy(p.x, p.y) - arc - cam.tw * 0.2f
            val target = world.entity(p.targetId) ?: continue
            val dx = cam.sx(target.x, target.y) - sx
            val dy = cam.sy(target.x, target.y) - sy
            val d = max(1f, sqrt(dx * dx + dy * dy))
            val len = cam.tw * 0.18f
            drawLine(Color(0xFF3B2A1A), Offset(sx - dx / d * len, sy - dy / d * len), Offset(sx, sy), strokeWidth = max(1.5f, cam.tw / 40f))
        }

        updateFog()
        drawMapImage(fogImage, n)

        // Overlays on top of fog
        for (e in drawList) {
            if (e is GameUnit && (c.isSelected(e.id) || e.hp < e.maxHp)) drawHpBar(e.x, e.y, cam.tw * 0.55f, e.hp / e.maxHp, e.owner, cam.tw * 0.5f)
            if (e is Building && e.type != BuildingType.FARM && (c.isSelected(e.id) || e.hp < e.maxHp * 0.999f)) {
                drawHpBar(e.x, e.y, cam.tw * 0.9f, e.hp / e.maxHp, e.owner, buildingHeight(e.type) + cam.th * e.size * 0.5f + cam.tw * 0.25f)
            }
            if (e is ResourceNode && c.isSelected(e.id)) drawFootprint(e.tx, e.ty, 1, Color.White, 2f)
        }
        c.selectedBuilding()?.takeIf { it.owner == 0 && it.rallyX >= 0f }?.let { drawRally(it) }
        for (m in c.markers) {
            val r = cam.tw * 0.3f * (m.life / 0.6f + 0.3f)
            drawOval(
                if (m.attack) Color(0xFFFF4040) else Color(0xFF7CFF6A),
                topLeft = Offset(cam.sx(m.x, m.y) - r, cam.sy(m.x, m.y) - r / 2f),
                size = Size(r * 2f, r),
                style = Stroke(width = 3f),
            )
        }
        c.placing?.let { type ->
            val ok = c.ghostValid()
            drawFootprintFill(c.ghostTx, c.ghostTy, type.size, if (ok) Color(0x6650FF50) else Color(0x66FF4040))
            drawFootprint(c.ghostTx, c.ghostTy, type.size, if (ok) Color(0xFF7CFF6A) else Color(0xFFFF5050), 3f)
            if (type != BuildingType.FARM) {
                drawBox(
                    c.ghostTx.toFloat(), c.ghostTy.toFloat(), type.size.toFloat(), buildingHeight(type),
                    Color(0x88D8D0C0), Color(0x88B0A890), Color(0x88E8E0D0),
                )
            }
        }
        c.box?.let {
            drawRect(Color(0x337CFF6A), topLeft = it.topLeft, size = it.size)
            drawRect(Color(0xFF7CFF6A), topLeft = it.topLeft, size = it.size, style = Stroke(2f))
        }
    }

    private fun depth(e: Entity): Float = when (e) {
        is Building -> e.tx + e.ty + e.size * 1f
        else -> e.x + e.y
    }

    // ------------------------------------------------------------------ Hit test

    fun hitTest(px: Float, py: Float): Entity? {
        val tol = max(cam.tw * 0.3f, 28f)
        var best: Entity? = null
        var bestD = Float.MAX_VALUE
        for (u in world.units) {
            if (!u.alive || !world.canSee(u)) continue
            val sx = cam.sx(u.x, u.y)
            val sy = cam.sy(u.x, u.y) - cam.tw * 0.25f
            val d = abs(px - sx) + abs(py - sy) * 0.8f
            if (d < tol + cam.tw * 0.15f && d < bestD) {
                bestD = d
                best = u
            }
        }
        if (best != null) return best
        val w = cam.toWorld(px, py)
        // Buildings: footprint or their raised body.
        for (b in world.buildings.sortedByDescending { it.tx + it.ty }) {
            if (!b.alive || !world.canSee(b)) continue
            val inFootprint = w.x >= b.tx && w.x <= b.tx + b.size && w.y >= b.ty && w.y <= b.ty + b.size
            val h = if (b.type == BuildingType.FARM) 0f else buildingHeight(b.type)
            val wh = cam.toWorld(px, py + h * 0.8f)
            val inBody = wh.x >= b.tx && wh.x <= b.tx + b.size && wh.y >= b.ty && wh.y <= b.ty + b.size
            if (inFootprint || inBody) return b
        }
        for (nd in world.nodes) {
            if (!nd.alive || !world.canSee(nd)) continue
            val sx = cam.sx(nd.x, nd.y)
            val sy = cam.sy(nd.x, nd.y) - if (nd.kind == NodeKind.TREE) cam.tw * 0.35f else 0f
            val d = abs(px - sx) + abs(py - sy)
            if (d < tol + cam.tw * 0.2f && d < bestD) {
                bestD = d
                best = nd
            }
        }
        return best
    }

    // ------------------------------------------------------------------ Primitives

    private fun ptX(x: Float, y: Float) = cam.sx(x, y)
    private fun ptY(x: Float, y: Float) = cam.sy(x, y)

    private fun DrawScope.drawFootprint(tx: Int, ty: Int, s: Int, color: Color, width: Float) {
        path.reset()
        path.moveTo(ptX(tx.toFloat(), ty.toFloat()), ptY(tx.toFloat(), ty.toFloat()))
        path.lineTo(ptX(tx + s.toFloat(), ty.toFloat()), ptY(tx + s.toFloat(), ty.toFloat()))
        path.lineTo(ptX(tx + s.toFloat(), ty + s.toFloat()), ptY(tx + s.toFloat(), ty + s.toFloat()))
        path.lineTo(ptX(tx.toFloat(), ty + s.toFloat()), ptY(tx.toFloat(), ty + s.toFloat()))
        path.close()
        drawPath(path, color, style = Stroke(width))
    }

    private fun DrawScope.drawFootprintFill(tx: Int, ty: Int, s: Int, color: Color) {
        path.reset()
        path.moveTo(ptX(tx.toFloat(), ty.toFloat()), ptY(tx.toFloat(), ty.toFloat()))
        path.lineTo(ptX(tx + s.toFloat(), ty.toFloat()), ptY(tx + s.toFloat(), ty.toFloat()))
        path.lineTo(ptX(tx + s.toFloat(), ty + s.toFloat()), ptY(tx + s.toFloat(), ty + s.toFloat()))
        path.lineTo(ptX(tx.toFloat(), ty + s.toFloat()), ptY(tx.toFloat(), ty + s.toFloat()))
        path.close()
        drawPath(path, color)
    }

    private fun DrawScope.poly(color: Color, vararg pts: Float) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            path.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        path.close()
        drawPath(path, color)
    }

    /** Draws an isometric box on a footprint (with a small inset) and returns nothing. */
    private fun DrawScope.drawBox(
        tx: Float, ty: Float, s: Float, h: Float,
        left: Color, right: Color, top: Color?, inset: Float = 0.12f,
    ) {
        val x0 = tx + inset
        val y0 = ty + inset
        val x1 = tx + s - inset
        val y1 = ty + s - inset
        val lX = ptX(x0, y1)
        val lY = ptY(x0, y1)
        val bX = ptX(x1, y1)
        val bY = ptY(x1, y1)
        val rX = ptX(x1, y0)
        val rY = ptY(x1, y0)
        val tX = ptX(x0, y0)
        val tY = ptY(x0, y0)
        poly(left, lX, lY, bX, bY, bX, bY - h, lX, lY - h)
        poly(right, bX, bY, rX, rY, rX, rY - h, bX, bY - h)
        if (top != null) poly(top, tX, tY - h, rX, rY - h, bX, bY - h, lX, lY - h)
    }

    private fun DrawScope.drawPyramidRoof(tx: Float, ty: Float, s: Float, h: Float, roofH: Float, left: Color, right: Color, inset: Float = 0.02f) {
        val x0 = tx + inset
        val y0 = ty + inset
        val x1 = tx + s - inset
        val y1 = ty + s - inset
        val cx = ptX(tx + s / 2f, ty + s / 2f)
        val cy = ptY(tx + s / 2f, ty + s / 2f) - h - roofH
        val lX = ptX(x0, y1)
        val lY = ptY(x0, y1) - h
        val bX = ptX(x1, y1)
        val bY = ptY(x1, y1) - h
        val rX = ptX(x1, y0)
        val rY = ptY(x1, y0) - h
        poly(left, lX, lY, bX, bY, cx, cy)
        poly(right, bX, bY, rX, rY, cx, cy)
    }

    private fun DrawScope.drawFlag(x: Float, y: Float, owner: Int, time: Float) {
        val u = cam.tw / 64f
        val color = Color(world.players[owner].color)
        drawLine(Color(0xFF4A3A28), Offset(x, y), Offset(x, y - 18 * u), strokeWidth = 1.5f * u)
        val wave = sin(time * 6f + x * 0.01f) * 1.5f * u
        poly(color, x, y - 18 * u, x + 11 * u, y - 15 * u + wave, x, y - 11 * u)
    }

    private fun DrawScope.drawHpBar(x: Float, y: Float, w: Float, ratio: Float, owner: Int, raise: Float) {
        val sx = cam.sx(x, y) - w / 2f
        val sy = cam.sy(x, y) - raise - cam.tw * 0.2f
        val h = max(3f, cam.tw / 18f)
        drawRect(Color(0xCC000000), topLeft = Offset(sx - 1, sy - 1), size = Size(w + 2, h + 2))
        val col = when {
            owner == 0 -> Color(0xFF4CD04C)
            owner < 0 -> Color(0xFFE0C050)
            else -> Color(0xFFE04040)
        }
        drawRect(col, topLeft = Offset(sx, sy), size = Size(w * ratio.coerceIn(0f, 1f), h))
    }

    private fun DrawScope.drawSelectionRing(u: GameUnit) {
        val r = cam.tw * (u.type.radius + 0.12f)
        val sx = cam.sx(u.x, u.y)
        val sy = cam.sy(u.x, u.y)
        drawOval(Color.White, topLeft = Offset(sx - r, sy - r / 2f), size = Size(r * 2, r), style = Stroke(width = max(1.5f, cam.tw / 40f)))
    }

    private fun DrawScope.drawRally(b: Building) {
        val x = cam.sx(b.rallyX, b.rallyY)
        val y = cam.sy(b.rallyX, b.rallyY)
        drawLine(Color(0x88FFFFFF), Offset(cam.sx(b.x, b.y), cam.sy(b.x, b.y)), Offset(x, y), strokeWidth = 2f)
        drawFlag(x, y, b.owner, world.time)
    }

    // ------------------------------------------------------------------ Resources

    private fun DrawScope.drawNode(nd: ResourceNode) {
        val u = cam.tw / 64f
        val x = cam.sx(nd.x, nd.y)
        val y = cam.sy(nd.x, nd.y)
        when (nd.kind) {
            NodeKind.TREE -> {
                drawOval(Color(0x44000000), Offset(x - 14 * u, y - 5 * u), Size(28 * u, 10 * u))
                val v = nd.variant
                val scale = 0.85f + (v % 5) * 0.06f
                drawRect(Color(0xFF5A3B1E), Offset(x - 2.5f * u, y - 18 * u * scale), Size(5 * u, 18 * u * scale))
                if (v % 3 == 0) {
                    // Pine
                    val g1 = Color(0xFF2E5A23)
                    val g2 = Color(0xFF3D7330)
                    for (i in 0 until 3) {
                        val top = y - (52 - i * 10) * u * scale
                        val w = (12 + i * 5) * u * scale
                        poly(if (i % 2 == 0) g1 else g2, x, top, x + w, top + 20 * u * scale, x - w, top + 20 * u * scale)
                    }
                } else {
                    val g = if (v % 2 == 0) Color(0xFF3F7A2E) else Color(0xFF4C8A34)
                    drawCircle(Color(0xFF2F5E22), 13 * u * scale, Offset(x - 5 * u, y - 28 * u * scale))
                    drawCircle(g, 13 * u * scale, Offset(x + 5 * u, y - 30 * u * scale))
                    drawCircle(Color(0xFF5A9A3E), 9 * u * scale, Offset(x, y - 38 * u * scale))
                }
            }
            NodeKind.BERRIES -> {
                drawOval(Color(0x44000000), Offset(x - 13 * u, y - 4 * u), Size(26 * u, 9 * u))
                drawCircle(Color(0xFF2E6B2A), 11 * u, Offset(x - 4 * u, y - 9 * u))
                drawCircle(Color(0xFF3A8034), 10 * u, Offset(x + 5 * u, y - 10 * u))
                val berries = (nd.amount / nd.maxHp * 9).toInt() + 1
                for (i in 0 until berries) {
                    val a = i * 2.4f + nd.variant
                    drawCircle(Color(0xFFC8243A), 2.4f * u, Offset(x + cos(a) * 9 * u, y - 10 * u + sin(a) * 6 * u))
                }
            }
            NodeKind.GOLD, NodeKind.STONE -> {
                val gold = nd.kind == NodeKind.GOLD
                val dark = if (gold) Color(0xFF8C6D1F) else Color(0xFF6E6E6E)
                val mid = if (gold) Color(0xFFD4A82A) else Color(0xFF9A9A98)
                val light = if (gold) Color(0xFFF7DB6A) else Color(0xFFC4C4C0)
                drawOval(Color(0x44000000), Offset(x - 16 * u, y - 5 * u), Size(32 * u, 11 * u))
                poly(dark, x - 15 * u, y, x - 8 * u, y - 14 * u, x + 2 * u, y - 16 * u, x + 4 * u, y)
                poly(mid, x - 4 * u, y + 2 * u, x + 2 * u, y - 18 * u, x + 12 * u, y - 12 * u, x + 15 * u, y + 1 * u)
                poly(light, x + 2 * u, y - 18 * u, x + 8 * u, y - 15 * u, x + 4 * u, y - 9 * u)
                if (gold) drawCircle(Color(0xFFFFF2A8), 1.8f * u, Offset(x - 6 * u, y - 8 * u))
            }
        }
    }

    // ------------------------------------------------------------------ Buildings

    fun buildingHeight(t: BuildingType): Float {
        val tw = cam.tw
        return when (t) {
            BuildingType.TOWN_CENTER -> tw * 0.7f
            BuildingType.HOUSE -> tw * 0.38f
            BuildingType.MILL -> tw * 0.42f
            BuildingType.LUMBER_CAMP, BuildingType.MINING_CAMP -> tw * 0.3f
            BuildingType.FARM -> 0f
            BuildingType.BARRACKS -> tw * 0.55f
            BuildingType.ARCHERY_RANGE, BuildingType.STABLE -> tw * 0.45f
            BuildingType.BLACKSMITH -> tw * 0.45f
            BuildingType.WATCH_TOWER -> tw * 1.5f
            BuildingType.SIEGE_WORKSHOP -> tw * 0.5f
            BuildingType.WONDER -> tw * 0.6f
        }
    }

    private fun DrawScope.drawFarm(b: Building) {
        val tx = b.tx.toFloat()
        val ty = b.ty.toFloat()
        val s = b.size.toFloat()
        val inset = 0.06f
        poly(
            Color(if (b.complete) 0xFF7A5A30 else 0xAA7A5A30),
            ptX(tx + inset, ty + inset), ptY(tx + inset, ty + inset),
            ptX(tx + s - inset, ty + inset), ptY(tx + s - inset, ty + inset),
            ptX(tx + s - inset, ty + s - inset), ptY(tx + s - inset, ty + s - inset),
            ptX(tx + inset, ty + s - inset), ptY(tx + inset, ty + s - inset),
        )
        val ratio = if (b.complete) (b.food / world.farmFood(world.players[b.owner])).coerceIn(0f, 1f) else b.progress
        val rows = 6
        val crop = Color(0xFF8DB43A).copy(alpha = 0.35f + 0.65f * ratio)
        for (i in 1 until rows) {
            val fy = ty + s * i / rows
            drawLine(
                crop, Offset(ptX(tx + 0.15f, fy), ptY(tx + 0.15f, fy)), Offset(ptX(tx + s - 0.15f, fy), ptY(tx + s - 0.15f, fy)),
                strokeWidth = max(2f, cam.tw / 14f * (0.4f + ratio)), cap = StrokeCap.Round,
            )
        }
    }

    private fun DrawScope.drawBuilding(b: Building, time: Float) {
        val tx = b.tx.toFloat()
        val ty = b.ty.toFloat()
        val s = b.size.toFloat()
        val fullH = buildingHeight(b.type)
        val player = Color(world.players[b.owner].color)
        val playerDark = darken(player, 0.65f)
        val stoneL = Color(0xFFC7BCA2)
        val stoneR = Color(0xFF9A8F78)
        val woodL = Color(0xFFA77B4C)
        val woodR = Color(0xFF7D5A36)
        // Shadow
        poly(
            Color(0x33000000),
            ptX(tx, ty + s), ptY(tx, ty + s),
            ptX(tx + s, ty + s), ptY(tx + s, ty + s),
            ptX(tx + s + 0.5f, ty + s - 0.2f), ptY(tx + s + 0.5f, ty + s - 0.2f),
            ptX(tx + s + 0.5f, ty - 0.2f), ptY(tx + s + 0.5f, ty - 0.2f),
        )
        if (!b.complete) {
            drawFootprintFill(b.tx, b.ty, b.size, Color(0x667A5A30))
            val h = fullH * b.progress
            drawBox(tx, ty, s, h, woodL.copy(alpha = 0.75f), woodR.copy(alpha = 0.75f), null)
            // Scaffolding poles
            val pole = Color(0xFF6B4A2A)
            for ((px, py) in listOf(tx + 0.1f to ty + s - 0.1f, tx + s - 0.1f to ty + s - 0.1f, tx + s - 0.1f to ty + 0.1f)) {
                drawLine(pole, Offset(ptX(px, py), ptY(px, py)), Offset(ptX(px, py), ptY(px, py) - fullH), strokeWidth = max(1.5f, cam.tw / 32f))
            }
            return
        }
        when (b.type) {
            BuildingType.TOWN_CENTER -> {
                drawBox(tx, ty, s, fullH, stoneL, stoneR, null)
                // Door
                drawDoor(tx + s * 0.75f, ty + s - 0.12f, fullH * 0.45f)
                drawPyramidRoof(tx, ty, s, fullH, cam.tw * 0.55f, playerDark, darken(playerDark, 0.75f), inset = -0.05f)
                // Little bell tower
                drawBox(tx + s / 2f - 0.35f, ty + s / 2f - 0.35f, 0.7f, fullH + cam.tw * 0.75f, stoneL, stoneR, null, inset = 0f)
                drawPyramidRoof(tx + s / 2f - 0.35f, ty + s / 2f - 0.35f, 0.7f, fullH + cam.tw * 0.75f, cam.tw * 0.3f, player, playerDark, inset = -0.05f)
                val fx = ptX(tx + s / 2f, ty + s / 2f)
                val fy = ptY(tx + s / 2f, ty + s / 2f) - fullH - cam.tw * 1.05f
                drawFlag(fx, fy, b.owner, time)
            }
            BuildingType.HOUSE -> {
                drawBox(tx, ty, s, fullH, woodL, woodR, null)
                drawDoor(tx + s * 0.6f, ty + s - 0.12f, fullH * 0.6f)
                drawPyramidRoof(tx, ty, s, fullH, cam.tw * 0.35f, Color(0xFF9A4530), Color(0xFF73321F), inset = -0.03f)
                flagOn(b, fullH + cam.tw * 0.35f, time)
            }
            BuildingType.MILL -> {
                drawBox(tx, ty, s, fullH, stoneL, stoneR, null)
                drawPyramidRoof(tx, ty, s, fullH, cam.tw * 0.35f, Color(0xFF8A6A3A), Color(0xFF6A4E28))
                // Windmill blades
                val cx = ptX(tx + s - 0.1f, ty + s * 0.5f)
                val cy = ptY(tx + s - 0.1f, ty + s * 0.5f) - fullH * 0.95f
                val len = cam.tw * 0.55f
                for (i in 0 until 4) {
                    val a = time * 1.5f + i * PI.toFloat() / 2f
                    drawLine(Color(0xFFEDE3C8), Offset(cx, cy), Offset(cx + cos(a) * len, cy + sin(a) * len), strokeWidth = cam.tw / 14f)
                }
                drawCircle(Color(0xFF5A3B1E), cam.tw / 20f, Offset(cx, cy))
                flagOn(b, fullH + cam.tw * 0.35f, time)
            }
            BuildingType.LUMBER_CAMP, BuildingType.MINING_CAMP -> {
                val lumber = b.type == BuildingType.LUMBER_CAMP
                drawBox(tx, ty, s, fullH, if (lumber) woodL else stoneL, if (lumber) woodR else stoneR, Color(0xFF5E4630))
                // Resource pile
                val px = ptX(tx + s * 0.75f, ty + s + 0.15f)
                val py = ptY(tx + s * 0.75f, ty + s + 0.15f)
                val u = cam.tw / 64f
                if (lumber) {
                    for (i in 0 until 3) drawCircle(Color(0xFF8B5E34), 4 * u, Offset(px + (i - 1) * 8 * u, py - 4 * u))
                    for (i in 0 until 2) drawCircle(Color(0xFF8B5E34), 4 * u, Offset(px + (i - 0.5f) * 8 * u, py - 11 * u))
                } else {
                    poly(Color(0xFFD4A82A), px - 10 * u, py, px, py - 10 * u, px + 10 * u, py)
                }
                flagOn(b, fullH, time)
            }
            BuildingType.BARRACKS -> {
                drawBox(tx, ty, s, fullH, stoneL, stoneR, Color(0xFF7E705A))
                drawCrenels(tx, ty, s, fullH, stoneL)
                drawDoor(tx + s * 0.5f, ty + s - 0.12f, fullH * 0.5f)
                flagOn(b, fullH, time)
            }
            BuildingType.ARCHERY_RANGE -> {
                drawBox(tx, ty, s, fullH, woodL, woodR, null)
                drawPyramidRoof(tx, ty, s, fullH, cam.tw * 0.35f, Color(0xFF55703A), Color(0xFF3E5429))
                // Target on the right wall
                val cx = ptX(tx + s - 0.12f, ty + s * 0.5f)
                val cy = ptY(tx + s - 0.12f, ty + s * 0.5f) - fullH * 0.5f
                drawCircle(Color.White, cam.tw * 0.14f, Offset(cx, cy))
                drawCircle(Color(0xFFD03030), cam.tw * 0.09f, Offset(cx, cy))
                flagOn(b, fullH + cam.tw * 0.35f, time)
            }
            BuildingType.STABLE -> {
                drawBox(tx, ty, s, fullH, woodL, woodR, null)
                drawDoor(tx + s * 0.4f, ty + s - 0.12f, fullH * 0.7f, wide = true)
                drawPyramidRoof(tx, ty, s, fullH, cam.tw * 0.35f, Color(0xFF7A5230), Color(0xFF5B3B20))
                flagOn(b, fullH + cam.tw * 0.35f, time)
            }
            BuildingType.BLACKSMITH -> {
                drawBox(tx, ty, s, fullH, stoneL, stoneR, Color(0xFF55493A))
                val cx = tx + s * 0.3f
                val cy = ty + s * 0.3f
                drawBox(cx, cy, 0.35f, fullH + cam.tw * 0.35f, Color(0xFF6E6252), Color(0xFF574C3F), Color(0xFF3B332A), inset = 0f)
                val sx = ptX(cx + 0.17f, cy + 0.17f)
                val sy = ptY(cx + 0.17f, cy + 0.17f) - fullH - cam.tw * 0.4f
                for (i in 0 until 3) {
                    val t = (time * 0.6f + i / 3f) % 1f
                    drawCircle(Color(0xFF888888).copy(alpha = 0.5f * (1 - t)), cam.tw * (0.06f + t * 0.1f), Offset(sx + t * cam.tw * 0.2f, sy - t * cam.tw * 0.6f))
                }
                drawDoor(tx + s * 0.6f, ty + s - 0.12f, fullH * 0.55f)
                flagOn(b, fullH, time)
            }
            BuildingType.WATCH_TOWER -> {
                drawBox(tx + 0.1f, ty + 0.1f, s - 0.2f, fullH, stoneL, stoneR, null)
                drawPyramidRoof(tx, ty, s, fullH, cam.tw * 0.45f, player, playerDark, inset = 0.02f)
                flagOn(b, fullH + cam.tw * 0.45f, time)
            }
            BuildingType.SIEGE_WORKSHOP -> {
                drawBox(tx, ty, s, fullH, woodL, woodR, null)
                drawDoor(tx + s * 0.5f, ty + s - 0.12f, fullH * 0.75f, wide = true)
                drawPyramidRoof(tx, ty, s, fullH, cam.tw * 0.3f, Color(0xFF6E6E6E), Color(0xFF555555))
                flagOn(b, fullH + cam.tw * 0.3f, time)
            }
            BuildingType.WONDER -> {
                val gold = Color(0xFFE2C25A)
                val goldD = Color(0xFFB8963A)
                var h = 0f
                for (i in 0 until 3) {
                    val inset = i * 0.45f
                    drawBox(tx + inset, ty + inset, s - inset * 2, h + fullH, if (i % 2 == 0) stoneL else gold, if (i % 2 == 0) stoneR else goldD, null)
                    h += fullH * 0.9f
                }
                drawPyramidRoof(tx + 1.35f, ty + 1.35f, s - 2.7f, h + fullH * 0.1f, cam.tw * 0.8f, gold, goldD)
                if (b.wonderTimer > 0f) flagOn(b, h + cam.tw * 0.9f, time)
            }
            BuildingType.FARM -> Unit
        }
        if (b.hp < b.maxHp * 0.5f) drawFire(b, fullH, time)
    }

    private fun DrawScope.flagOn(b: Building, h: Float, time: Float) {
        val x = ptX(b.x, b.y)
        val y = ptY(b.x, b.y) - h
        drawFlag(x, y, b.owner, time)
    }

    private fun DrawScope.drawDoor(x: Float, y: Float, h: Float, wide: Boolean = false) {
        // Door on the left (south-west) face; x/y are world coords on the wall base.
        val w = if (wide) 0.45f else 0.25f
        val ax = ptX(x - w / 2f, y)
        val ay = ptY(x - w / 2f, y)
        val bx = ptX(x + w / 2f, y)
        val by = ptY(x + w / 2f, y)
        poly(Color(0xFF3E2A18), ax, ay, bx, by, bx, by - h, ax, ay - h)
    }

    private fun DrawScope.drawCrenels(tx: Float, ty: Float, s: Float, h: Float, color: Color) {
        val count = (s * 2).toInt()
        val ch = cam.tw * 0.12f
        for (i in 0 until count) {
            if (i % 2 == 1) continue
            val f = (i + 0.5f) / count
            drawBox(tx + 0.12f + (s - 0.24f) * f - 0.12f, ty + s - 0.36f, 0.24f, h + ch, color, darken(color, 0.8f), color, inset = 0f)
        }
    }

    private fun DrawScope.drawFire(b: Building, h: Float, time: Float) {
        val count = if (b.hp < b.maxHp * 0.25f) 3 else 1
        for (i in 0 until count) {
            val fx = b.tx + b.size * (0.3f + 0.25f * i)
            val fy = b.ty + b.size * (0.7f - 0.2f * i)
            val x = ptX(fx, fy)
            val y = ptY(fx, fy) - h * 0.8f
            val flick = sin(time * 12f + i * 2f) * cam.tw * 0.02f
            drawCircle(Color(0xCCFF6A00), cam.tw * 0.12f + flick, Offset(x, y))
            drawCircle(Color(0xDDFFD040), cam.tw * 0.06f, Offset(x, y - cam.tw * 0.04f))
        }
    }

    // ------------------------------------------------------------------ Units

    private fun DrawScope.drawUnit(un: GameUnit, time: Float) {
        val u = cam.tw / 64f
        val x = cam.sx(un.x, un.y)
        val y = cam.sy(un.x, un.y)
        val dir = if (un.facingLeft) -1f else 1f
        val player = Color(world.players[un.owner].color)
        val walk = if (un.moving) sin(un.animTime * 12f) else 0f
        val working = (un.task == Task.GATHER || un.task == Task.BUILD) && !un.moving
        val swing = when {
            working -> sin(un.animTime * 8f)
            un.task == Task.ATTACK && un.cooldown > un.type.reload - 0.35f -> -1f + (un.type.reload - un.cooldown) * 4f
            else -> 0f
        }
        when (un.type.category) {
            Category.CAVALRY -> drawRider(un, x, y, u, dir, player, walk, swing)
            Category.SIEGE -> drawRam(x, y, u, dir, player, walk)
            else -> drawFootman(un, x, y, u, dir, player, walk, swing)
        }
    }

    private fun DrawScope.drawFootman(un: GameUnit, x: Float, y: Float, u: Float, dir: Float, player: Color, walk: Float, swing: Float) {
        drawOval(Color(0x44000000), Offset(x - 8 * u, y - 3 * u), Size(16 * u, 6 * u))
        val leg = Color(0xFF4A3524)
        drawLine(leg, Offset(x - 2 * u, y - 8 * u), Offset(x - 2 * u + walk * 3 * u, y), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
        drawLine(leg, Offset(x + 2 * u, y - 8 * u), Offset(x + 2 * u - walk * 3 * u, y), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
        val bodyColor = if (un.type == UnitType.VILLAGER) lighten(player, 0.15f) else player
        drawRoundRect(bodyColor, Offset(x - 5 * u, y - 19 * u), Size(10 * u, 12 * u), CornerRadius(3 * u))
        drawLine(Color(0xFF3B2A1A), Offset(x - 5 * u, y - 10 * u), Offset(x + 5 * u, y - 10 * u), strokeWidth = 1.4f * u)
        val skin = Color(0xFFE2B48A)
        drawCircle(skin, 4 * u, Offset(x, y - 23 * u))
        val handX = x + dir * 5 * u
        val handY = y - 14 * u
        when (un.type) {
            UnitType.VILLAGER -> {
                // Straw hat
                drawOval(Color(0xFFD8B860), Offset(x - 6 * u, y - 28 * u), Size(12 * u, 4 * u))
                drawCircle(Color(0xFFD8B860), 2.6f * u, Offset(x, y - 28 * u))
                val node = world.entity(un.targetId)
                val toolColor = Color(0xFF9A9A9A)
                if (un.task == Task.GATHER || un.task == Task.BUILD) {
                    val a = -1.2f + swing * 0.8f
                    val ex = handX + dir * cos(a) * 10 * u
                    val ey = handY + sin(a) * 10 * u
                    drawLine(Color(0xFF6B4A2A), Offset(handX, handY), Offset(ex, ey), strokeWidth = 1.8f * u)
                    when {
                        un.task == Task.BUILD -> drawRect(toolColor, Offset(ex - 2.5f * u, ey - 2 * u), Size(5 * u, 4 * u))
                        node is ResourceNode && node.kind == NodeKind.TREE -> poly(toolColor, ex, ey, ex + dir * 4 * u, ey + 1 * u, ex, ey + 4 * u)
                        node is ResourceNode && (node.kind == NodeKind.GOLD || node.kind == NodeKind.STONE) ->
                            drawLine(toolColor, Offset(ex - 4 * u, ey + 2 * u), Offset(ex + 4 * u, ey - 2 * u), strokeWidth = 1.8f * u)
                        else -> Unit
                    }
                }
                if (un.carryAmount > 0f) {
                    val col = when (un.carryType) {
                        ResourceType.FOOD -> Color(0xFFC8243A)
                        ResourceType.WOOD -> Color(0xFF8B5E34)
                        ResourceType.GOLD -> Color(0xFFE8C23A)
                        ResourceType.STONE -> Color(0xFFA8A8A8)
                        null -> Color.Transparent
                    }
                    drawRoundRect(col, Offset(x - dir * 9 * u - 3.5f * u, y - 20 * u), Size(7 * u, 8 * u), CornerRadius(2 * u))
                }
            }
            UnitType.ARCHER -> {
                drawArc(Color(0xFF3F6B2C), 180f, 180f, true, Offset(x - 4.5f * u, y - 28 * u), Size(9 * u, 9 * u))
                val bx = handX + dir * 2 * u
                drawArc(
                    Color(0xFF6B4A2A), if (dir > 0) -70f else 110f, 140f, false,
                    Offset(bx - 7 * u, handY - 9 * u), Size(14 * u, 18 * u), style = Stroke(1.8f * u),
                )
            }
            else -> {
                // Helmet
                drawArc(Color(0xFF8C8C8C), 180f, 180f, true, Offset(x - 4.6f * u, y - 28 * u), Size(9.2f * u, 9 * u))
                if (un.type == UnitType.SPEARMAN) {
                    val tilt = swing * 4 * u
                    drawLine(Color(0xFF6B4A2A), Offset(handX, y - 4 * u), Offset(handX + dir * (3 * u + tilt), y - 36 * u), strokeWidth = 1.8f * u)
                    poly(Color(0xFFBDBDBD), handX + dir * (3 * u + tilt) - 2 * u, y - 36 * u, handX + dir * (3 * u + tilt) + 2 * u, y - 36 * u, handX + dir * (3 * u + tilt), y - 41 * u)
                } else {
                    val a = -1.0f + swing * 1.1f
                    drawLine(Color(0xFFD8D8D8), Offset(handX, handY), Offset(handX + dir * cos(a) * 12 * u, handY + sin(a) * 12 * u), strokeWidth = 2 * u, cap = StrokeCap.Round)
                }
                // Shield
                drawCircle(darken(player, 0.75f), 4.5f * u, Offset(x - dir * 4 * u, y - 13 * u))
                drawCircle(Color(0xFFD8C080), 1.3f * u, Offset(x - dir * 4 * u, y - 13 * u))
            }
        }
    }

    private fun DrawScope.drawRider(un: GameUnit, x: Float, y: Float, u: Float, dir: Float, player: Color, walk: Float, swing: Float) {
        drawOval(Color(0x44000000), Offset(x - 14 * u, y - 4 * u), Size(28 * u, 8 * u))
        val knight = un.type == UnitType.KNIGHT
        val horse = if (knight) Color(0xFFE6E2D8) else Color(0xFF7A4E2A)
        val horseDark = darken(horse, 0.75f)
        // Legs
        for (i in 0 until 4) {
            val lx = x + (i - 1.5f) * 6 * u
            val sw = if (i % 2 == 0) walk else -walk
            drawLine(horseDark, Offset(lx, y - 9 * u), Offset(lx + sw * 3 * u, y), strokeWidth = 2.4f * u, cap = StrokeCap.Round)
        }
        drawOval(horse, Offset(x - 12 * u, y - 18 * u), Size(24 * u, 11 * u))
        if (knight) drawRect(player, Offset(x - 9 * u, y - 16 * u), Size(18 * u, 6 * u))
        // Neck & head
        drawLine(horse, Offset(x + dir * 9 * u, y - 14 * u), Offset(x + dir * 14 * u, y - 23 * u), strokeWidth = 5 * u, cap = StrokeCap.Round)
        drawOval(horse, Offset(x + dir * 15 * u - 4 * u, y - 26 * u), Size(8 * u, 5 * u))
        drawLine(Color(0xFF2A1A10), Offset(x + dir * 8 * u, y - 18 * u), Offset(x + dir * 13 * u, y - 25 * u), strokeWidth = 1.5f * u)
        // Rider
        drawRoundRect(player, Offset(x - 4.5f * u, y - 30 * u), Size(9 * u, 12 * u), CornerRadius(3 * u))
        drawCircle(Color(0xFFE2B48A), 3.8f * u, Offset(x, y - 33 * u))
        drawArc(if (knight) Color(0xFFB0B0B0) else Color(0xFF6B4A2A), 180f, 180f, true, Offset(x - 4.4f * u, y - 38 * u), Size(8.8f * u, 8.6f * u))
        if (knight) {
            val a = -0.35f + swing * 0.4f
            drawLine(Color(0xFF8A6A3A), Offset(x, y - 24 * u), Offset(x + dir * cos(a) * 26 * u, y - 24 * u + sin(a) * 26 * u), strokeWidth = 2 * u, cap = StrokeCap.Round)
        } else {
            val a = -1.0f + swing * 1.1f
            drawLine(Color(0xFFD8D8D8), Offset(x + dir * 4 * u, y - 25 * u), Offset(x + dir * 4 * u + dir * cos(a) * 10 * u, y - 25 * u + sin(a) * 10 * u), strokeWidth = 1.8f * u)
        }
    }

    private fun DrawScope.drawRam(x: Float, y: Float, u: Float, dir: Float, player: Color, walk: Float) {
        drawOval(Color(0x44000000), Offset(x - 16 * u, y - 5 * u), Size(32 * u, 10 * u))
        val wood = Color(0xFF8A6238)
        drawRect(darken(wood, 0.8f), Offset(x - 14 * u, y - 14 * u), Size(28 * u, 9 * u))
        poly(wood, x - 16 * u, y - 13 * u, x + 16 * u, y - 13 * u, x + 8 * u, y - 26 * u, x - 8 * u, y - 26 * u)
        drawLine(player, Offset(x - 12 * u, y - 15 * u), Offset(x + 12 * u, y - 15 * u), strokeWidth = 2.5f * u)
        drawLine(Color(0xFF5A3B1E), Offset(x + dir * 10 * u, y - 10 * u), Offset(x + dir * 20 * u, y - 10 * u), strokeWidth = 4 * u)
        for (i in listOf(-1f, 1f)) {
            drawCircle(Color(0xFF4A3220), 4 * u, Offset(x + i * 9 * u, y - 4 * u))
            drawLine(Color(0xFF8A6A3A), Offset(x + i * 9 * u, y - 4 * u), Offset(x + i * 9 * u + cos(walk * 3f) * 3 * u, y - 4 * u + sin(walk * 3f) * 3 * u), strokeWidth = u)
        }
    }

    companion object {
        private const val TEX = 8

        fun darken(c: Color, f: Float) = Color(c.red * f, c.green * f, c.blue * f, c.alpha)
        fun lighten(c: Color, f: Float) = Color(min(1f, c.red + (1 - c.red) * f), min(1f, c.green + (1 - c.green) * f), min(1f, c.blue + (1 - c.blue) * f), c.alpha)
    }
}
