package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme

/** Keeps scrolled content from running under the status bar icons on edge-to-edge screens. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    val background = MetroPulseTheme.colors.background
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(Brush.verticalGradient(listOf(background, background.copy(alpha = 0.94f)))),
    )
}
