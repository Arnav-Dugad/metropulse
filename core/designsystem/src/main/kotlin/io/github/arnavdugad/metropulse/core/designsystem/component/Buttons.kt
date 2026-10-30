package io.github.arnavdugad.metropulse.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes

/** Scales content down slightly while pressed, on a firm spring. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f, MetroPulseTheme.motion.press(), label = "pressScale")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .heightIn(min = 56.dp)
            .shadow(if (enabled) 16.dp else 0.dp, MpShapes.pill, ambientColor = colors.accent, spotColor = colors.accent)
            .clip(MpShapes.pill)
            .background(if (enabled) colors.tripBrush else androidx.compose.ui.graphics.SolidColor(colors.hairline))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                haptics.confirm()
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MetroPulseTheme.type.bodyStrong, color = if (enabled) colors.onAccent else colors.textTertiary, textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    container: Color = MetroPulseTheme.colors.accentSoft,
    contentColor: Color = MetroPulseTheme.colors.accent,
) {
    val haptics = rememberMpHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .heightIn(min = 52.dp)
            .clip(MpShapes.pill)
            .background(container)
            .clickable(interaction, indication = null, role = Role.Button) {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MetroPulseTheme.type.bodyStrong, color = contentColor, textAlign = TextAlign.Center)
    }
}

/** Low-emphasis text action. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MetroPulseTheme.colors.textSecondary) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberMpHaptics()
    Box(
        modifier
            .pressScale(interaction)
            .clip(MpShapes.pill)
            .clickable(interaction, indication = null, role = Role.Button) { haptics.tick(); onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MetroPulseTheme.type.label, color = color)
    }
}

/**
 * A primary button that morphs into a circular progress ring while work runs. Width springs from
 * full to a circle; the label cross-fades to the percentage.
 */
@Composable
fun ProgressPillButton(
    text: String,
    progress: Float?,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    busyLabel: String = "",
) {
    val colors = MetroPulseTheme.colors
    val motion = MetroPulseTheme.motion
    val haptics = rememberMpHaptics()
    val interaction = remember { MutableInteractionSource() }
    BoxWithConstraints(modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
        val width by animateDpAsState(if (busy) 60.dp else maxWidth, motion.bouncy(), label = "pillWidth")
        val ring by animateFloatAsState(progress ?: 0f, motion.standard(), label = "ring")
        Box(
            Modifier
                .width(width)
                .height(60.dp)
                .pressScale(interaction)
                .shadow(18.dp, MpShapes.pill, ambientColor = colors.accent, spotColor = colors.accent)
                .clip(MpShapes.pill)
                .background(colors.tripBrush)
                .clickable(interaction, indication = null, enabled = !busy, role = Role.Button) {
                    haptics.confirm()
                    onClick()
                }
                .semantics {
                    if (busy && progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = busy,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.8f)) },
                label = "pillContent",
            ) { isBusy ->
                if (isBusy) {
                    Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
                        if (progress == null) {
                            androidx.compose.material3.CircularProgressIndicator(
                                color = colors.onAccent, trackColor = Color.White.copy(alpha = 0.2f),
                                strokeWidth = 3.dp, modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Canvas(Modifier.fillMaxSize()) {
                                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                drawArc(Color.White.copy(alpha = 0.2f), 0f, 360f, false, style = stroke)
                                drawArc(Color.White, -90f, 360f * ring, false, style = stroke)
                            }
                            Text(busyLabel, style = MetroPulseTheme.type.caption, color = colors.onAccent)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) {
                            Icon(icon, null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
                            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                        }
                        Text(text, style = MetroPulseTheme.type.bodyStrong, color = colors.onAccent, maxLines = 1)
                    }
                }
            }
        }
    }
}
