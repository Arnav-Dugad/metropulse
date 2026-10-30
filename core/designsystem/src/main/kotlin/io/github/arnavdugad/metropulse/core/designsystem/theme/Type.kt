package io.github.arnavdugad.metropulse.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arnavdugad.metropulse.core.designsystem.R

private fun inter(weight: Int) = Font(
    resId = R.font.inter_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter, bundled as a single variable font (SIL OFL 1.1). */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800))

/** Tabular figures keep countdown digits from jittering as they change. */
private const val TABULAR = "tnum, cv11"

@Immutable
data class MpTypography(
    /** Huge countdown numerals on the trip card. */
    val numeralHero: TextStyle,
    val numeral: TextStyle,
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    /** Small all-caps eyebrow text above titles. */
    val overline: TextStyle,
)

internal val DefaultTypography = MpTypography(
    numeralHero = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 60.sp, lineHeight = 60.sp, letterSpacing = (-0.04).em, fontFeatureSettings = TABULAR),
    numeral = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.02).em, fontFeatureSettings = TABULAR),
    display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.025).em),
    headline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.015).em),
    title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em),
    body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyStrong = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.005.em),
    caption = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TABULAR),
    overline = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em),
)

/**
 * Lets this text grow with the system font size only up to [maxScale]. For short, fixed-width
 * labels (tab names) and very large titles; body text keeps scaling fully.
 */
@Composable
@ReadOnlyComposable
fun TextStyle.scaledAtMost(maxScale: Float): TextStyle {
    val scale = LocalDensity.current.fontScale
    if (scale <= maxScale) return this
    val factor = maxScale / scale
    return copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
}
