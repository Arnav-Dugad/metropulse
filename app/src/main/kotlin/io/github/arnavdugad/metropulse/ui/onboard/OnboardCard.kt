package io.github.arnavdugad.metropulse.ui.onboard

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.GradientSurface
import io.github.arnavdugad.metropulse.core.designsystem.component.RollingText
import io.github.arnavdugad.metropulse.core.designsystem.component.ShimmerProgressBar
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.onboard.PositionSource
import io.github.arnavdugad.metropulse.core.domain.onboard.TripPhase
import io.github.arnavdugad.metropulse.onboard.TripSession
import io.github.arnavdugad.metropulse.ui.common.lineBrush
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter

/** The active trip: destination, countdown, stops left and progress, on the violet gradient. */
@Composable
fun OnboardCard(session: TripSession, onEnd: () -> Unit, modifier: Modifier = Modifier) {
    val p = session.progress
    val network = session.network
    val clock = rememberClockFormatter()
    val white = Color.White
    val context = LocalContext.current
    // The card takes the colour of the line the rider is on.
    val brush = lineBrush(listOfNotNull(network.lineById[p.ride.lineId]), MetroPulseTheme.colors.tripBrush)
    GradientSurface(modifier.fillMaxWidth(), brush = brush) {
        Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeSourceChip(
                    when (p.source) {
                        PositionSource.Live -> TimeSource.Live
                        PositionSource.Scheduled -> TimeSource.Scheduled
                        PositionSource.Approximate -> TimeSource.Approximate
                    },
                    onGradient = true,
                )
                Spacer(Modifier.weight(1f))
                val shareLabel = stringResource(R.string.onboard_share_eta)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) {
                            val text = etaText(context, session, clock)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                        }
                        .semantics { contentDescription = shareLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MpIcons.Share, null, tint = white.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                }
                val endLabel = stringResource(R.string.onboard_end_trip)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onEnd)
                        .semantics { contentDescription = endLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MpIcons.Close, null, tint = white.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(if (p.phase == TripPhase.Arrived) R.string.onboard_card_arrived else R.string.onboard_card_to).uppercase(),
                style = MetroPulseTheme.type.overline,
                color = white.copy(alpha = 0.7f),
            )
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 8.dp)) {
                Text(session.toName, style = MetroPulseTheme.type.headline, color = white, modifier = Modifier.weight(1f))
                val secs = p.secondsToDestination
                if (p.phase != TripPhase.Arrived && secs != null) {
                    val minutes = ((secs + 30) / 60).coerceAtLeast(1)
                    RollingText(
                        text = minutes.toString(),
                        style = MetroPulseTheme.type.numeral,
                        color = white,
                        accessibilityText = stringResource(R.string.onboard_chip_minutes, minutes),
                    )
                    Text(" " + stringResource(R.string.onboard_min), style = MetroPulseTheme.type.bodyStrong, color = white.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(statusLine(session, clock), style = MetroPulseTheme.type.body, color = white.copy(alpha = 0.85f))
            when {
                p.wrongDirection -> Warning(stringResource(R.string.onboard_wrong_direction, p.ride.headsign))
                p.missedStop -> Warning(stringResource(R.string.onboard_missed_stop, network.stationById[p.ride.toStationId]?.name.orEmpty()))
                p.phase != TripPhase.Arrived -> LocationHint()
            }
            Spacer(Modifier.height(14.dp))
            ShimmerProgressBar(p.fraction.toFloat(), Modifier.fillMaxWidth().padding(end = 8.dp))
        }
    }
}

@Composable
private fun statusLine(session: TripSession, clock: (Int) -> String): String {
    val p = session.progress
    val network = session.network
    val line = network.lineById[p.ride.lineId]?.name.orEmpty()
    return when (p.phase) {
        TripPhase.Arrived -> stringResource(R.string.onboard_arrived_body)
        TripPhase.WaitingToBoard -> {
            val from = network.stationById[p.ride.fromStationId]?.name.orEmpty()
            val departs = p.ride.departSecs
            if (departs != null) stringResource(R.string.onboard_card_board_timed, line, p.ride.headsign, from, clock(departs))
            else stringResource(R.string.onboard_card_board, line, p.ride.headsign, from)
        }
        TripPhase.Riding -> {
            val next = network.patternById[p.ride.patternId]?.stationIds?.getOrNull(p.nextStopIndex)?.let { network.stationById[it]?.name }.orEmpty()
            val stops = pluralStringResource(R.plurals.onboard_stops, p.stopsToDestination, p.stopsToDestination)
            val change = if (p.rideIndex < session.journey.rides.lastIndex) {
                " · " + stringResource(R.string.onboard_card_change_at, network.stationById[p.ride.toStationId]?.name.orEmpty())
            } else {
                ""
            }
            stringResource(R.string.onboard_card_riding, stops, next) + change
        }
    }
}

/**
 * Without location permission or with location turned off, the trip follows the timetable. Say so,
 * with a way to fix it. Rechecked whenever the app comes back to the foreground.
 */
@Composable
private fun LocationHint() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(locationStatus(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { status = locationStatus(context) }
    if (status == LocationStatus.Ok) return
    Row(
        Modifier
            .padding(top = 10.dp, end = 8.dp)
            .clip(MpShapes.control)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(role = Role.Button) {
                val intent = if (status == LocationStatus.NoPermission) {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                } else {
                    Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                }
                runCatching { context.startActivity(intent) }
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MpIcons.Locate, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(
            stringResource(if (status == LocationStatus.NoPermission) R.string.onboard_location_denied else R.string.onboard_location_off),
            style = MetroPulseTheme.type.label,
            color = Color.White,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(stringResource(R.string.onboard_location_fix), style = MetroPulseTheme.type.label, color = Color.White, textDecoration = TextDecoration.Underline)
    }
}

private enum class LocationStatus { Ok, NoPermission, Off }

private fun locationStatus(context: Context): LocationStatus {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!granted) return LocationStatus.NoPermission
    val manager = context.getSystemService(LocationManager::class.java) ?: return LocationStatus.Off
    return if (LocationManagerCompat.isLocationEnabled(manager)) LocationStatus.Ok else LocationStatus.Off
}

@Composable
private fun Warning(text: String) {
    Row(
        Modifier
            .padding(top = 10.dp, end = 8.dp)
            .clip(MpShapes.control)
            .background(MetroPulseTheme.colors.danger.copy(alpha = 0.9f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(MpIcons.Info, null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(text, style = MetroPulseTheme.type.label, color = Color.White)
    }
}

/** "On the Purple Line to Majestic, arriving about 9:42 am (MetroPulse)". */
private fun etaText(context: Context, session: TripSession, clock: (Int) -> String): String {
    val p = session.progress
    val line = session.network.lineById[p.ride.lineId]?.name.orEmpty()
    val secs = p.secondsToDestination
    return if (secs != null && p.phase != TripPhase.Arrived) {
        val arrive = java.time.ZonedDateTime.now(session.network.timezone).toLocalTime().toSecondOfDay() + secs
        context.getString(R.string.onboard_share_text, line, session.toName, clock(arrive))
    } else {
        context.getString(R.string.onboard_share_text_untimed, line, session.toName)
    }
}
