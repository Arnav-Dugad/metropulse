package io.github.arnavdugad.metropulse.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpColors
import io.github.arnavdugad.metropulse.update.ReleaseNotes.Block
import io.github.arnavdugad.metropulse.update.ReleaseNotes.Inline

@Composable
fun ReleaseNotesView(markdown: String, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val type = MetroPulseTheme.type
    val blocks = remember(markdown) { ReleaseNotes.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Heading -> Text(
                    block.content.toAnnotated(colors),
                    style = if (block.level <= 2) type.bodyStrong else type.label,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .padding(top = if (index == 0) 0.dp else 6.dp)
                        .semantics { heading() },
                )
                is Block.Bullet -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .size(6.dp)
                            .background(if (block.depth == 0) colors.accent else colors.textTertiary, CircleShape),
                    )
                    Spacer6()
                    Text(block.content.toAnnotated(colors), style = type.body, color = colors.textSecondary)
                }
                is Block.Numbered -> Row {
                    Text("${block.number}.", style = type.bodyStrong, color = colors.accent)
                    Spacer6()
                    Text(block.content.toAnnotated(colors), style = type.body, color = colors.textSecondary)
                }
                is Block.Paragraph -> Text(block.content.toAnnotated(colors), style = type.body, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun Spacer6() = androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))

private fun List<Inline>.toAnnotated(colors: MpColors): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.None))
    forEach { part ->
        when (part) {
            is Inline.Text -> append(part.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = colors.textPrimary)) { append(part.text) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.accentSoft)) { append(part.text) }
            is Inline.Link -> withLink(LinkAnnotation.Url(part.url, linkStyles)) { append(part.label) }
        }
    }
}
