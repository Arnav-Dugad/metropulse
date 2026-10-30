package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme

/** Centered illustration, title and one line of context. Springs in on first appearance. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, MetroPulseTheme.motion.bouncy(), label = "empty")
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .graphicsLayer {
                alpha = progress.coerceIn(0f, 1f)
                translationY = (1f - progress) * 24.dp.toPx()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LoopEmblem(icon)
        Spacer(Modifier.size(24.dp))
        Text(title, style = MetroPulseTheme.type.headline, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(body, style = MetroPulseTheme.type.body, color = colors.textSecondary, textAlign = TextAlign.Center)
    }
}
