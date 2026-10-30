package io.github.arnavdugad.metropulse.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Raw palette. Screens should read [MpColors] through [MetroPulseTheme.colors], not these values. */
internal object Palette {
    val Violet950 = Color(0xFF12062E)
    val Violet900 = Color(0xFF1E0B4A)
    val Violet800 = Color(0xFF2B1070)
    val Violet700 = Color(0xFF3F1BA3)
    val Violet600 = Color(0xFF5428D6)
    val Violet500 = Color(0xFF6C3BFF)
    val Violet400 = Color(0xFF8D68FF)
    val Violet300 = Color(0xFFB39DFF)
    val Violet200 = Color(0xFFD5C9FF)
    val Violet100 = Color(0xFFEEE9FF)

    val Ink = Color(0xFF110F18)
    val Graphite = Color(0xFF5F5B6E)
    val Mist = Color(0xFFF3F2F8)
    val Night = Color(0xFF0A0910)
    val NightSurface = Color(0xFF15131D)
    val NightElevated = Color(0xFF1D1A28)

    val Live = Color(0xFF16C784)
    val Amber = Color(0xFFF5A524)
    val Danger = Color(0xFFF0475A)
}

@Immutable
data class MpColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    /** Translucent fill for glass cards when backdrop blur is unavailable. */
    val glass: Color,
    val glassBorder: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val live: Color,
    val scheduled: Color,
    val approximate: Color,
    val danger: Color,
    /** The deep purple-to-violet gradient used for the active trip card and primary actions. */
    val tripGradient: List<Color>,
    val shadow: Color,
) {
    val tripBrush: Brush get() = Brush.linearGradient(tripGradient)
}

internal val LightMpColors = MpColors(
    isDark = false,
    background = Palette.Mist,
    surface = Color.White,
    surfaceElevated = Color.White,
    glass = Color.White.copy(alpha = 0.72f),
    glassBorder = Color.White.copy(alpha = 0.85f),
    hairline = Palette.Ink.copy(alpha = 0.08f),
    textPrimary = Palette.Ink,
    textSecondary = Palette.Graphite,
    textTertiary = Palette.Graphite.copy(alpha = 0.7f),
    accent = Palette.Violet500,
    accentSoft = Palette.Violet100,
    onAccent = Color.White,
    live = Color(0xFF0FA968),
    scheduled = Palette.Graphite,
    approximate = Color(0xFFB7770A),
    danger = Palette.Danger,
    tripGradient = listOf(Palette.Violet800, Palette.Violet600, Palette.Violet400),
    shadow = Palette.Violet900.copy(alpha = 0.18f),
)

internal val DarkMpColors = MpColors(
    isDark = true,
    background = Palette.Night,
    surface = Palette.NightSurface,
    surfaceElevated = Palette.NightElevated,
    glass = Palette.NightElevated.copy(alpha = 0.66f),
    glassBorder = Color.White.copy(alpha = 0.10f),
    hairline = Color.White.copy(alpha = 0.08f),
    textPrimary = Color(0xFFF5F3FB),
    textSecondary = Color(0xFFA19DB2),
    textTertiary = Color(0xFFA19DB2).copy(alpha = 0.7f),
    accent = Palette.Violet400,
    accentSoft = Palette.Violet900,
    onAccent = Color.White,
    live = Palette.Live,
    scheduled = Color(0xFFA19DB2),
    approximate = Palette.Amber,
    danger = Palette.Danger,
    tripGradient = listOf(Palette.Violet950, Palette.Violet700, Palette.Violet500),
    shadow = Color.Black.copy(alpha = 0.5f),
)

/** Stronger text and borders for the high-contrast setting; glass becomes nearly opaque. */
internal fun MpColors.highContrast(): MpColors = copy(
    glass = surface.copy(alpha = 0.97f),
    glassBorder = textPrimary.copy(alpha = 0.5f),
    hairline = textPrimary.copy(alpha = 0.3f),
    textSecondary = textPrimary.copy(alpha = 0.86f),
    textTertiary = textPrimary.copy(alpha = 0.75f),
    accent = if (isDark) Palette.Violet300 else Palette.Violet700,
)
