package io.github.arnavdugad.metropulse.core.designsystem.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes

/** Where a time came from. Every time shown in the app carries one of these. */
enum class TimeSource { Live, Scheduled, Approximate }

/**
 * The Live / Scheduled / Approximate badge. Live gets a breathing dot; the others a clock glyph,
 * so the difference never depends on colour alone.
 */
@Composable
fun TimeSourceChip(
    source: TimeSource,
    modifier: Modifier = Modifier,
    onGradient: Boolean = false,
) {
    val colors = MetroPulseTheme.colors
    val (label, tint) = when (source) {
        TimeSource.Live -> "Live" to colors.live
        TimeSource.Scheduled -> "Scheduled" to colors.scheduled
        TimeSource.Approximate -> "Approx." to colors.approximate
    }
    val content = if (onGradient) Color.White else tint
    Row(
        modifier
            .clip(MpShapes.pill)
            .background(if (onGradient) Color.White.copy(alpha = 0.16f) else tint.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (source == TimeSource.Live) {
            LiveDot(color = if (onGradient) colors.live else tint)
        } else {
            Icon(MpIcons.Clock, null, tint = content, modifier = Modifier.size(13.dp))
        }
        Text(label, style = MetroPulseTheme.type.overline, color = content)
    }
}

@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier) {
    val reduced = MetroPulseTheme.motion.reduced
    val pulse = if (reduced) 0f else rememberInfiniteTransition(label = "live").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "pulse",
    ).value
    Box(modifier.size(8.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { scaleX = 1f + pulse * 1.4f; scaleY = 1f + pulse * 1.4f; alpha = 1f - pulse }
                .background(color.copy(alpha = 0.5f), CircleShape),
        )
        Box(Modifier.size(7.dp).background(color, CircleShape))
    }
}

/** Small rounded tag, e.g. a version number or file size. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = MetroPulseTheme.colors.accent) {
    Row(
        modifier
            .clip(MpShapes.pill)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, style = MetroPulseTheme.type.label, color = tint)
    }
}
