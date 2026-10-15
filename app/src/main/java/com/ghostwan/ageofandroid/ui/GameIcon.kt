package com.ghostwan.ageofandroid.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit

/**
 * Shows an emoji icon, or a hand-drawn one when the id starts with "@" (for things Unicode has
 * no emoji for, like a wheelbarrow).
 */
@Composable
fun GameIcon(icon: String, size: TextUnit, dim: Boolean = false) {
    if (!icon.startsWith("@")) {
        Text(icon, fontSize = size, color = if (dim) Color.Gray else Color.Unspecified)
        return
    }
    val side = with(LocalDensity.current) { (size * 1.2f).toDp() }
    Canvas(Modifier.size(side)) {
        val alpha = if (dim) 0.45f else 1f
        when (icon) {
            "@wheelbarrow" -> drawWheelbarrow(alpha)
            "@axe" -> drawAxe(alpha)
        }
    }
}

private fun DrawScope.drawWheelbarrow(alpha: Float) {
    val w = size.width
    val h = size.height
    val wood = Color(0xFFB07A42).copy(alpha = alpha)
    val woodDark = Color(0xFF7A4E26).copy(alpha = alpha)
    val metal = Color(0xFF9A9A9A).copy(alpha = alpha)
    // Handles going up-right, and the support leg.
    drawLine(woodDark, Offset(w * 0.30f, h * 0.55f), Offset(w * 0.96f, h * 0.30f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    drawLine(woodDark, Offset(w * 0.72f, h * 0.55f), Offset(w * 0.78f, h * 0.84f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
    // Tray (trapezoid)
    val tray = Path().apply {
        moveTo(w * 0.06f, h * 0.30f)
        lineTo(w * 0.70f, h * 0.30f)
        lineTo(w * 0.60f, h * 0.60f)
        lineTo(w * 0.22f, h * 0.60f)
        close()
    }
    drawPath(tray, wood)
    drawPath(tray, woodDark, style = Stroke(w * 0.035f))
    // Load
    drawCircle(Color(0xFF6E8E3A).copy(alpha = alpha), w * 0.12f, Offset(w * 0.30f, h * 0.27f))
    drawCircle(Color(0xFF8DB43A).copy(alpha = alpha), w * 0.10f, Offset(w * 0.47f, h * 0.25f))
    // Wheel
    drawLine(metal, Offset(w * 0.24f, h * 0.60f), Offset(w * 0.24f, h * 0.78f), strokeWidth = w * 0.04f)
    drawCircle(Color(0xFF3A2A1A).copy(alpha = alpha), w * 0.14f, Offset(w * 0.24f, h * 0.80f))
    drawCircle(metal, w * 0.05f, Offset(w * 0.24f, h * 0.80f))
}

private fun DrawScope.drawAxe(alpha: Float) {
    val w = size.width
    val h = size.height
    val handle = Color(0xFF9A6A38).copy(alpha = alpha)
    val blade = Color(0xFFC8CCD0).copy(alpha = alpha)
    val edge = Color(0xFF7E8488).copy(alpha = alpha)
    drawLine(handle, Offset(w * 0.22f, h * 0.92f), Offset(w * 0.62f, h * 0.12f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
    // Double-bit head: two blades around the top of the handle.
    val cx = w * 0.56f
    val cy = h * 0.24f
    val right = Path().apply {
        moveTo(cx, cy - h * 0.06f)
        quadraticTo(cx + w * 0.38f, cy - h * 0.22f, cx + w * 0.34f, cy + h * 0.12f)
        quadraticTo(cx + w * 0.22f, cy + h * 0.10f, cx, cy + h * 0.08f)
        close()
    }
    val left = Path().apply {
        moveTo(cx, cy - h * 0.06f)
        quadraticTo(cx - w * 0.26f, cy - h * 0.28f, cx - w * 0.34f, cy - h * 0.02f)
        quadraticTo(cx - w * 0.16f, cy + h * 0.02f, cx, cy + h * 0.08f)
        close()
    }
    drawPath(right, blade)
    drawPath(left, blade)
    drawPath(right, edge, style = Stroke(w * 0.03f))
    drawPath(left, edge, style = Stroke(w * 0.03f))
}
