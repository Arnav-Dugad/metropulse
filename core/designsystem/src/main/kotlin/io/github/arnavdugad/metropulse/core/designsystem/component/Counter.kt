package io.github.arnavdugad.metropulse.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme

/**
 * Text where each character rolls independently when it changes: counting down slides digits
 * down, counting up slides them up. Non-digit characters (":", ".") stay put.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    countingDown: Boolean = true,
    accessibilityText: String = text,
) {
    val reduced = MetroPulseTheme.motion.reduced
    // Align from the right so "10" -> "9" animates the units digit rather than shifting everything.
    val chars = remember(text) { text.toList() }
    Row(modifier.clearAndSetSemantics { contentDescription = accessibilityText }) {
        chars.forEachIndexed { index, ch ->
            val slot = chars.size - index
            androidx.compose.runtime.key(slot) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        if (reduced || !initialState.isDigit() || !targetState.isDigit()) {
                            fadeIn() togetherWith fadeOut()
                        } else {
                            val dir = if (countingDown) -1 else 1
                            (slideInVertically(spring(dampingRatio = 0.8f, stiffness = 420f)) { -dir * it } + fadeIn()) togetherWith
                                (slideOutVertically(spring(dampingRatio = 0.8f, stiffness = 420f)) { dir * it } + fadeOut())
                        }.using(SizeTransform(clip = true))
                    },
                    label = "digit",
                ) { c ->
                    Text(c.toString(), style = style, color = color)
                }
            }
        }
    }
}
