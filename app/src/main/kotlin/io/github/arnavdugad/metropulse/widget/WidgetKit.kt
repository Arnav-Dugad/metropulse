package io.github.arnavdugad.metropulse.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.user.UserRepository
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import io.github.arnavdugad.metropulse.onboard.OnboardController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Singletons the widgets read; widgets are not Hilt entry points themselves. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetDependencies {
    fun cities(): CityRepository
    fun user(): UserRepository
    fun onboard(): OnboardController
}

internal fun Context.widgetDependencies(): WidgetDependencies =
    EntryPointAccessors.fromApplication(applicationContext, WidgetDependencies::class.java)

/** The current city once loaded, or null if none is chosen or it does not load in time. */
internal suspend fun WidgetDependencies.readyCity(): LoadedCity? =
    withTimeoutOrNull(8_000) { cities().state.first { it !is CityState.Loading } }
        .let { (it as? CityState.Ready)?.city }

/** Colours that follow the system's light and dark mode (values / values-night). */
internal object WidgetColors {
    val background = ColorProvider(R.color.widget_background)
    val surface = ColorProvider(R.color.widget_surface)
    val textPrimary = ColorProvider(R.color.widget_text_primary)
    val textSecondary = ColorProvider(R.color.widget_text_secondary)
    val accent = ColorProvider(R.color.widget_accent)
    val onAccent = ColorProvider(R.color.widget_on_accent)
}

/** Black lines (London's Northern line) are grey on the dark widget background, where black would vanish. */
internal fun lineColour(rgb: Int) =
    if (rgb and 0xFFFFFF == 0) DayNightColor(day = Color.Black, night = Color(0xFF8E919C))
    else ColorProvider(Color(0xFF000000 or rgb.toLong()))

@Composable
internal fun WidgetText(
    text: String,
    size: TextUnit = 13.sp,
    color: ColorProvider = WidgetColors.textPrimary,
    bold: Boolean = false,
    maxLines: Int = 1,
    modifier: GlanceModifier = GlanceModifier,
) {
    Text(
        text,
        modifier = modifier,
        maxLines = maxLines,
        style = TextStyle(color = color, fontSize = size, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
}

@Composable
internal fun Dot(colour: ColorProvider, size: Dp = 10.dp) {
    Image(ImageProvider(R.drawable.widget_dot), contentDescription = null, colorFilter = ColorFilter.tint(colour), modifier = GlanceModifier.size(size))
}

@Composable
internal fun WidgetIcon(res: Int, tint: ColorProvider = WidgetColors.accent, size: Dp = 18.dp) {
    Image(ImageProvider(res), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = GlanceModifier.size(size))
}

/** A small pill such as "Scheduled". */
@Composable
internal fun WidgetTag(text: String) {
    Box(
        GlanceModifier.background(ImageProvider(R.drawable.widget_tag)).padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        WidgetText(text, size = 11.sp, color = WidgetColors.textSecondary, bold = true)
    }
}
