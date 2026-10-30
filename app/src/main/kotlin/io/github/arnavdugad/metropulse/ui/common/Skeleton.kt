package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes

/** A sweep of light across loading placeholders (still when motion is reduced). */
@Composable
fun rememberShimmerBrush(): Brush {
    val colors = MetroPulseTheme.colors
    val base = colors.textPrimary.copy(alpha = if (colors.isDark) 0.07f else 0.06f)
    val light = colors.textPrimary.copy(alpha = if (colors.isDark) 0.16f else 0.13f)
    if (MetroPulseTheme.motion.reduced) return Brush.linearGradient(listOf(base, base))
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(
        -1f, 2f, infiniteRepeatable(tween(1_300, easing = LinearEasing), RepeatMode.Restart), label = "x",
    )
    return Brush.linearGradient(
        listOf(base, light, base),
        start = androidx.compose.ui.geometry.Offset(t * 900f - 300f, 0f),
        end = androidx.compose.ui.geometry.Offset(t * 900f + 300f, 300f),
    )
}

@Composable
fun SkeletonBlock(width: Dp?, height: Dp, brush: Brush, modifier: Modifier = Modifier, shape: Shape = MpShapes.control) {
    Box((if (width != null) modifier.width(width) else modifier.fillMaxWidth()).height(height).clip(shape).background(brush))
}

/** Placeholder rows shaped like the cards about to appear (lines, departures). */
@Composable
fun SkeletonList(label: String, rows: Int = 5, modifier: Modifier = Modifier) {
    val brush = rememberShimmerBrush()
    Column(modifier.semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(rows) {
            Column(
                Modifier.fillMaxWidth().clip(MpShapes.card).background(MetroPulseTheme.colors.surface.copy(alpha = 0.7f)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SkeletonBlock(14.dp, 14.dp, brush, shape = CircleShape)
                    Box(Modifier.size(10.dp))
                    SkeletonBlock(140.dp, 16.dp, brush)
                }
                SkeletonBlock(null, 12.dp, brush)
                SkeletonBlock(180.dp, 12.dp, brush)
            }
        }
    }
}
