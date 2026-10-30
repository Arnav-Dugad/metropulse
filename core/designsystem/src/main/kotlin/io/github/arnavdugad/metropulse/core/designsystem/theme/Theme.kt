package io.github.arnavdugad.metropulse.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

private val LocalMpColors = staticCompositionLocalOf { LightMpColors }
private val LocalMpTypography = staticCompositionLocalOf { DefaultTypography }
private val LocalMpMotion = staticCompositionLocalOf { MpMotion(reduced = false) }

/** Corner radii. Generous on purpose; nested elements step down so curves stay concentric. */
object MpShapes {
    val sheet = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val card = RoundedCornerShape(28.dp)
    val inner = RoundedCornerShape(20.dp)
    val control = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(percent = 50)
}

@Composable
fun MetroPulseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme, highContrast) {
        (if (darkTheme) DarkMpColors else LightMpColors).let { if (highContrast) it.highContrast() else it }
    }
    val motion = remember(reducedMotion) { MpMotion(reducedMotion) }
    // Material components (sheets, ripples, text fields) pick up the same tokens.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            onBackground = colors.textPrimary, surface = colors.surface, onSurface = colors.textPrimary,
            surfaceContainerLow = colors.surface, surfaceContainer = colors.surfaceElevated,
            surfaceContainerHigh = colors.surfaceElevated, onSurfaceVariant = colors.textSecondary,
            outlineVariant = colors.hairline, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.onAccent, background = colors.background,
            onBackground = colors.textPrimary, surface = colors.surface, onSurface = colors.textPrimary,
            surfaceContainerLow = colors.surface, surfaceContainer = colors.surfaceElevated,
            surfaceContainerHigh = colors.surfaceElevated, onSurfaceVariant = colors.textSecondary,
            outlineVariant = colors.hairline, error = colors.danger,
        )
    }
    CompositionLocalProvider(
        LocalMpColors provides colors,
        LocalMpTypography provides DefaultTypography,
        LocalMpMotion provides motion,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object MetroPulseTheme {
    val colors: MpColors @Composable @ReadOnlyComposable get() = LocalMpColors.current
    val type: MpTypography @Composable @ReadOnlyComposable get() = LocalMpTypography.current
    val motion: MpMotion @Composable @ReadOnlyComposable get() = LocalMpMotion.current
}
