package io.github.arnavdugad.metropulse.onboard

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
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
    val still = MetroPulseTheme.motion.reduced
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 1f, targetValue = if (still) 1f else 1.18f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring",
    )
    // Two rings ripple outwards half a beat apart; a glow breathes behind everything.
    val ripple by transition.animateFloat(0f, if (still) 0f else 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "ripple")
    val glow by transition.animateFloat(0.25f, if (still) 0.25f else 0.55f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "glow")
    // The train runs into the station and starts again.
    val run by transition.animateFloat(0f, if (still) 0.85f else 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing)), label = "train")
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.tripBrush)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = glow * 0.35f), Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.36f),
                        radius = size.maxDimension * 0.6f,
                    ),
                )
            }
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.Center) {
                for (phase in listOf(0f, 0.5f)) {
                    val t = (ripple + phase) % 1f
                    Box(
                        Modifier.size(104.dp).graphicsLayer {
                            val scale = 1f + t * 1.1f
                            scaleX = scale; scaleY = scale
                            alpha = if (still) 0f else (1f - t) * 0.5f
                        }.border(2.dp, Color.White, CircleShape),
                    )
                }
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
            Spacer(Modifier.height(32.dp))
            ArrivingTrain(run, Modifier.fillMaxWidth().height(36.dp))
            Spacer(Modifier.height(24.dp))
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

/** A train sliding along the track into the station at the right, [progress] 0..1. */
@Composable
private fun ArrivingTrain(progress: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val y = size.height / 2
        val stationX = size.width - 14.dp.toPx()
        drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Color.White, radius = 9.dp.toPx(), center = Offset(stationX, y))
        drawCircle(Color.White.copy(alpha = 0.4f), radius = 5.dp.toPx(), center = Offset(stationX, y))
        val carW = 30.dp.toPx()
        val carH = 14.dp.toPx()
        val gap = 4.dp.toPx()
        val trainW = carW * 3 + gap * 2
        // Eases in to stop just short of the station, then fades for the next run.
        val head = -trainW + (stationX - 16.dp.toPx()) * progress.coerceAtMost(0.85f) / 0.85f
        val alpha = if (progress > 0.92f) (1f - progress) / 0.08f else 1f
        for (c in 0 until 3) {
            val x = head - (c + 1) * carW - c * gap + trainW
            drawRoundRect(
                Color.White.copy(alpha = 0.95f * alpha),
                topLeft = Offset(x, y - carH / 2),
                size = Size(carW, carH),
                cornerRadius = CornerRadius(if (c == 0) 7.dp.toPx() else 4.dp.toPx()),
            )
        }
    }
}
