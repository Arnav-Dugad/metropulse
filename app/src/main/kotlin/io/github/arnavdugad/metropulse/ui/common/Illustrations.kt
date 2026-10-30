package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * A small drawn metro map: three lines that cross at interchanges, stations, and a train running
 * along the first line. Used on the welcome page; still when motion is reduced.
 */
@Composable
fun NetworkScene(modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val reduced = MetroPulseTheme.motion.reduced
    val t by rememberInfiniteTransition(label = "scene").animateFloat(
        0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "train",
    )
    val lineColours = listOf(colors.tripGradient.first(), Color(0xFF2BB673), Color(0xFFF2A516))
    val stationFill = colors.surface
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 7.dp.toPx()
        // Lines in normalised coordinates: a sweeping main line, a crossing one, and a short branch.
        val paths = listOf(
            path(Offset(0.04f * w, 0.78f * h), Offset(0.35f * w, 0.78f * h), Offset(0.45f * w, 0.25f * h), Offset(0.96f * w, 0.25f * h)),
            path(Offset(0.18f * w, 0.08f * h), Offset(0.30f * w, 0.50f * h), Offset(0.70f * w, 0.70f * h), Offset(0.86f * w, 0.94f * h)),
            path(Offset(0.58f * w, 0.04f * h), Offset(0.60f * w, 0.30f * h), Offset(0.62f * w, 0.55f * h), Offset(0.96f * w, 0.62f * h)),
        )
        paths.forEachIndexed { i, p ->
            drawPath(p, lineColours[i].copy(alpha = 0.18f), style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
            drawPath(p, lineColours[i], style = Stroke(stroke, cap = StrokeCap.Round))
        }
        // Stations every so often along each line; bigger rings where lines meet.
        paths.forEachIndexed { i, p ->
            val m = PathMeasure().apply { setPath(p, false) }
            val n = 5
            for (k in 0..n) {
                val at = m.getPosition(m.length * k / n)
                drawCircle(stationFill, stroke * 0.85f, at)
                drawCircle(lineColours[i], stroke * 0.85f, at, style = Stroke(stroke * 0.45f))
            }
        }
        // The train: three cars following the curve of the first line.
        val main = PathMeasure().apply { setPath(paths[0], false) }
        val head = main.length * (if (reduced) 0.55f else t)
        for (c in 0 until 3) {
            val d = (head - c * 13.dp.toPx()).coerceAtLeast(0f)
            car(main, d, 12.dp.toPx(), 8.dp.toPx(), Color.White, colors.tripGradient.last())
        }
    }
}

private fun path(a: Offset, b: Offset, c: Offset, d: Offset) = Path().apply {
    moveTo(a.x, a.y)
    cubicTo(b.x, b.y, c.x, c.y, d.x, d.y)
}

private fun DrawScope.car(m: PathMeasure, distance: Float, length: Float, width: Float, fill: Color, edge: Color) {
    val at = m.getPosition(distance)
    val tangent = m.getTangent(distance)
    val angle = Math.toDegrees(atan2(tangent.y, tangent.x).toDouble()).toFloat()
    rotate(angle, at) {
        drawRoundRect(edge, Offset(at.x - length / 2 - 1.5f, at.y - width / 2 - 1.5f), Size(length + 3f, width + 3f), CornerRadius(width / 2))
        drawRoundRect(fill, Offset(at.x - length / 2, at.y - width / 2), Size(length, width), CornerRadius(width / 2))
    }
}

/**
 * The empty-state emblem: the screen's glyph on a gradient disc, ringed by a dotted loop line with
 * stations, and a small train going round it.
 */
@Composable
fun LoopEmblem(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 132.dp) {
    val colors = MetroPulseTheme.colors
    val reduced = MetroPulseTheme.motion.reduced
    val t by rememberInfiniteTransition(label = "loop").animateFloat(
        0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "orbit",
    )
    val accent = colors.accent
    val train = colors.tripGradient.last()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2 - 8.dp.toPx()
            val c = center
            drawCircle(accent.copy(alpha = 0.06f), this.size.minDimension / 2)
            drawCircle(
                accent.copy(alpha = 0.35f), r,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()))),
            )
            for (k in 0 until 4) {
                val a = Math.PI / 4 + k * Math.PI / 2
                drawCircle(accent.copy(alpha = 0.55f), 3.5.dp.toPx(), Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
            }
            val a = (if (reduced) 0.62 else t.toDouble()) * 2 * Math.PI
            val at = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            rotate(Math.toDegrees(a).toFloat() + 90f, at) {
                drawRoundRect(train, Offset(at.x - 9.dp.toPx(), at.y - 4.dp.toPx()), Size(18.dp.toPx(), 8.dp.toPx()), CornerRadius(4.dp.toPx()))
            }
        }
        Box(
            Modifier.size(size * 0.48f).clip(CircleShape).background(Brush.linearGradient(colors.tripGradient)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.21f))
        }
    }
}
