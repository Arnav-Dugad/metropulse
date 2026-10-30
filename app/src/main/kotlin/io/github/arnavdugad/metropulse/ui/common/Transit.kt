package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.transit.Line

/** The line's colour; a black line (London's Northern line) is drawn grey on dark surfaces, where black would vanish. */
@Composable
@ReadOnlyComposable
fun Line.composeColor(): Color =
    if (color and 0xFFFFFF == 0 && MetroPulseTheme.colors.isDark) Color(0xFF8E919C) else Color(0xFF000000 or color.toLong())

/**
 * A line marker: the operator's own round symbol where there is one (New York's bullets, Doha's
 * line icons), drawn a little larger; otherwise a dot in the line's colour.
 */
@Composable
fun LineDot(line: Line, size: Dp = 10.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { (size * BULLET_SCALE).roundToPx() }
    val key = remember(line.id) { Logos.lineKey(context, line.id) }
    val bullet by produceState<ImageBitmap?>(null, key, px) {
        value = key?.let { k -> withContext(Dispatchers.Default) { Logos.bitmap(context, k, px)?.takeIf { it.width <= it.height * 1.3f }?.asImageBitmap() } }
    }
    val b = bullet
    if (b != null) Image(b, null, modifier.size(size * BULLET_SCALE).symbolHalo(line))
    else Box(modifier.size(size).clip(CircleShape).background(line.composeColor()))
}

/** A line's name on a tint of its colour, led by its symbol (a roundel, a bullet) where it has one. */
@Composable
fun LineChip(line: Line, modifier: Modifier = Modifier, label: String = line.name) {
    val colour = line.composeColor()
    val context = LocalContext.current
    val logo = remember(line.id) { Logos.lineKey(context, line.id) }
    Row(
        modifier.clip(MpShapes.pill).background(colour.copy(alpha = 0.16f)).padding(start = if (logo != null) 6.dp else 10.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) Logo(logo, 18.dp, null, Modifier.symbolHalo(line)) else Box(Modifier.size(9.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MetroPulseTheme.type.label, color = MetroPulseTheme.colors.textPrimary, maxLines = 1)
    }
}

private const val BULLET_SCALE = 1.7f

/** A black symbol (the Northern line's roundel) sits on a light disc in dark mode, as it would on a station wall. */
@Composable
fun Modifier.symbolHalo(line: Line): Modifier =
    if (line.color and 0xFFFFFF == 0 && MetroPulseTheme.colors.isDark) background(Color(0xFFE9E9EE), CircleShape) else this

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MetroPulseTheme.type.overline,
        color = MetroPulseTheme.colors.textTertiary,
        modifier = modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** A station in a list: name, its lines as dots, an optional detail line and trailing content. */
@Composable
fun StationRow(
    name: String,
    lines: List<Line>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    leading: ImageVector? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    Row(
        modifier
            .fillMaxWidth()
            .clip(MpShapes.control)
            .clickable(role = Role.Button) { haptics.tick(); onClick() }
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MetroPulseTheme.type.caption, color = colors.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            lines.forEach { LineDot(it) }
        }
        trailing()
    }
}

/** Header for screens opened from a tab: back button, title and actions. */
@Composable
fun DetailTopBar(onBack: () -> Unit, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(MpIcons.Back, stringResource(R.string.action_back), onBack)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = MetroPulseTheme.colors.textPrimary) {
    val haptics = rememberMpHaptics()
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button) { haptics.tick(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** The pill search field used by every station search. */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    container: Color = MetroPulseTheme.colors.surface,
) {
    val colors = MetroPulseTheme.colors
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(MpShapes.pill)
            .background(container)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MpIcons.Search, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, style = MetroPulseTheme.type.body, color = colors.textTertiary)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MetroPulseTheme.type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) RoundIconButton(MpIcons.Close, stringResource(R.string.action_clear), { onQueryChange("") }, colors.textSecondary)
    }
}

// Shared-element transitions: the root provides the scope, each destination its animation scope.

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Morphs this element into the one with the same [key] on the next screen (a card into a header). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedElement(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedScope.current ?: return this
    if (MetroPulseTheme.motion.reduced) return this
    return with(shared) { this@sharedElement.sharedBounds(rememberSharedContentState(key), animated) }
}

/** One option of a small single-choice group (a time, a stop count, a theme). */
@Composable
fun ChoiceChip(text: String, selected: Boolean, idle: Color = MetroPulseTheme.colors.surface, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    Text(
        text,
        style = MetroPulseTheme.type.label,
        color = if (selected) colors.onAccent else colors.textPrimary,
        modifier = Modifier
            .clip(MpShapes.pill)
            .background(if (selected) colors.accent else idle)
            .clickable(role = Role.RadioButton) { haptics.tick(); onClick() }
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** The attribution a live feed's provider asks for ("Powered by TfL Open Data"). */
@Composable
fun LiveCredit(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MetroPulseTheme.type.caption, color = MetroPulseTheme.colors.textTertiary, modifier = modifier.padding(start = 8.dp, top = 8.dp))
}

/**
 * A gradient through the colours of [lines], deepened so white text stays readable on yellow and
 * pale lines; [fallback] (the brand gradient) when there are none.
 */
@Composable
fun lineBrush(lines: List<Line>, fallback: Brush): Brush {
    val colours = lines.distinctBy { it.id }.map { deepen(it.composeColor()) }
    return when (colours.size) {
        0 -> fallback
        1 -> Brush.linearGradient(listOf(colours[0], deepen(colours[0], 0.35f)))
        else -> Brush.linearGradient(colours)
    }
}

/** Mixes [c] towards black until white text on it has enough contrast. */
private fun deepen(c: Color, extra: Float = 0f): Color {
    var k = extra
    var out = lerp(c, Color.Black, k)
    while (out.luminance() > 0.28f && k < 0.8f) {
        k += 0.08f
        out = lerp(c, Color.Black, k)
    }
    return out
}
