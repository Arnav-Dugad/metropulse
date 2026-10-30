package io.github.arnavdugad.metropulse.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme

/**
 * Rounded progress bar whose filled part carries a light sweep, like light running along a rail.
 * The sweep stops when reduced motion is on.
 */
@Composable
fun ShimmerProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    track: Color = Color.White.copy(alpha = 0.18f),
    fill: Brush = Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White)),
) {
    val motion = MetroPulseTheme.motion
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), motion.gentle(), label = "progress")
    val sweep = if (motion.reduced) {
        -1f
    } else {
        val transition = rememberInfiniteTransition(label = "shimmer")
        transition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
            label = "sweep",
        ).value
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(animated, 0f..1f) },
    ) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        val filled = size.width * animated
        if (filled <= 0f) return@Canvas
        drawRoundRect(fill, size = Size(filled.coerceAtLeast(size.height), size.height), cornerRadius = radius)
        if (sweep >= -0.3f) {
            clipRect(right = filled) {
                val center = size.width * sweep
                val band = size.width * 0.18f
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent),
                        startX = center - band,
                        endX = center + band,
                    ),
                    topLeft = Offset(center - band, 0f),
                    size = Size(band * 2, size.height),
                )
            }
        }
    }
}
