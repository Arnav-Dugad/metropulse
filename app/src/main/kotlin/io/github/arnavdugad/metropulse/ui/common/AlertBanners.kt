package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.live.AlertSeverity
import io.github.arnavdugad.metropulse.core.domain.live.ServiceAlert

/** The colour that goes with a disruption's severity. */
@Composable
fun AlertSeverity.tint(): Color = when (this) {
    AlertSeverity.Closure -> MetroPulseTheme.colors.danger
    AlertSeverity.Severe -> Color(0xFFE5532D)
    AlertSeverity.Minor -> MetroPulseTheme.colors.approximate
    AlertSeverity.Info -> MetroPulseTheme.colors.textSecondary
}

/** Disruption banners, worst first; each opens to show the operator's full text. */
@Composable
fun AlertBanners(alerts: List<ServiceAlert>, modifier: Modifier = Modifier, max: Int = 4) {
    if (alerts.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        alerts.take(max).forEach { AlertBanner(it) }
        if (alerts.size > max) {
            Text(
                stringResource(R.string.alerts_more, alerts.size - max),
                style = MetroPulseTheme.type.caption,
                color = MetroPulseTheme.colors.textTertiary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun AlertBanner(alert: ServiceAlert) {
    val colors = MetroPulseTheme.colors
    val tint = alert.severity.tint()
    var open by rememberSaveable(alert.id) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MpShapes.inner)
            .background(tint.copy(alpha = if (colors.isDark) 0.16f else 0.1f))
            .border(1.dp, tint.copy(alpha = 0.35f), MpShapes.inner)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.alerts_less else R.string.alerts_read)) { open = !open }
            .animateContentSize()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MpIcons.Alert, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(alert.title, style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
        if (alert.body.isNotBlank()) {
            Text(
                alert.body,
                style = MetroPulseTheme.type.caption,
                color = colors.textSecondary,
                maxLines = if (open) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp, top = 4.dp),
            )
        }
    }
}

/** A compact status pill for a line ("Severe delays"), or nothing when it runs normally. */
@Composable
fun LineStatusPill(alerts: List<ServiceAlert>, modifier: Modifier = Modifier) {
    val worst = alerts.minByOrNull { it.severity } ?: return
    val tint = worst.severity.tint()
    Row(
        modifier.clip(MpShapes.pill).background(tint.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MpIcons.Alert, null, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(worst.title.substringAfter(": "), style = MetroPulseTheme.type.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
