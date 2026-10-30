package io.github.arnavdugad.metropulse.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes

/**
 * A frosted-glass surface. With a [hazeState] whose source is the content behind it, Haze blurs
 * that backdrop (RenderEffect on API 31+, a translucent scrim below). Without one it falls back
 * to a translucent fill with a light top edge, which still reads as glass over flat backgrounds.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    shape: Shape = MpShapes.card,
    elevation: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MetroPulseTheme.colors
    val glassStyle = HazeStyle(
        backgroundColor = colors.surface,
        tints = listOf(HazeTint(colors.glass.copy(alpha = if (colors.isDark) 0.55f else 0.6f))),
        blurRadius = 28.dp,
        noiseFactor = 0.06f,
        fallbackTint = HazeTint(colors.glass),
    )
    Box(
        modifier
            .shadow(elevation, shape, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(shape)
            .then(
                if (hazeState != null) Modifier.hazeEffect(state = hazeState, style = glassStyle)
                else Modifier.background(colors.glass),
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(listOf(colors.glassBorder, colors.glassBorder.copy(alpha = 0.15f))),
                shape = shape,
            ),
        content = content,
    )
}

/** The violet gradient card used for the active trip and other hero moments. */
@Composable
fun GradientSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MpShapes.card,
    brush: Brush = MetroPulseTheme.colors.tripBrush,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = MetroPulseTheme.colors
    Box(
        modifier
            .shadow(24.dp, shape, ambientColor = colors.tripGradient.last(), spotColor = colors.tripGradient[1])
            .clip(shape)
            .background(brush)
            // A soft sheen across the top edge gives the card its glassy depth.
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.45f to Color.Transparent))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.04f))), shape),
        content = content,
    )
}
