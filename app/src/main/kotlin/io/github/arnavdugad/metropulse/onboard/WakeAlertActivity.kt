package io.github.arnavdugad.metropulse.onboard

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.SecondaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme

/** Full-screen "your stop is next" card, shown over the lock screen by the wake alert. */
class WakeAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val destination = intent.getStringExtra(WakeAlert.EXTRA_DESTINATION).orEmpty()
        setContent {
            MetroPulseTheme {
                WakeAlertScreen(destination) {
                    WakeAlert.dismiss(this)
                    finish()
                }
            }
        }
    }
}

@Composable
private fun WakeAlertScreen(destination: String, onDismiss: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = if (MetroPulseTheme.motion.reduced) 1f else 1.18f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring",
    )
    Box(Modifier.fillMaxSize().background(colors.tripBrush).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(150.dp).graphicsLayer { scaleX = pulse; scaleY = pulse }.background(Color.White.copy(alpha = 0.12f), CircleShape))
                Box(Modifier.size(104.dp).background(Color.White.copy(alpha = 0.2f), CircleShape))
                Icon(MpIcons.Bell, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(36.dp))
            Text(stringResource(R.string.onboard_alert_title), style = MetroPulseTheme.type.title, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(8.dp))
            Text(destination, style = MetroPulseTheme.type.display, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.onboard_alert_body), style = MetroPulseTheme.type.body, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(40.dp))
            SecondaryButton(
                text = stringResource(R.string.onboard_alert_dismiss),
                onClick = { haptics.confirm(); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
                container = Color.White.copy(alpha = 0.18f),
                contentColor = Color.White,
            )
        }
    }
}
