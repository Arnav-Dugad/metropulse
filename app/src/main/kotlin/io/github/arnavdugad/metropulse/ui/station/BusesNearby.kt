package io.github.arnavdugad.metropulse.ui.station

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.designsystem.theme.tabular
import io.github.arnavdugad.metropulse.core.domain.live.BusMotion
import io.github.arnavdugad.metropulse.core.domain.live.NearbyBus
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.ui.common.LiveCredit
import io.github.arnavdugad.metropulse.ui.common.LiveViewModel
import io.github.arnavdugad.metropulse.ui.common.SectionTitle

/**
 * Buses reporting their position near the station, by route, nearest first (Delhi's DTC and
 * cluster buses). Positions are live but there are no predicted arrival times, so the rows give
 * distance and whether the bus is coming or going rather than minutes.
 */
@Composable
fun BusesNearby(network: TransitNetwork, complexId: String, liveViewModel: LiveViewModel, nowEpoch: Long) {
    val snapshot by remember(network, complexId) { liveViewModel.buses(network, complexId) }.collectAsStateWithLifecycle(null)
    val s = snapshot ?: return
    val colors = MetroPulseTheme.colors
    val routes = remember(s) { s.byRoute }
    var all by rememberSaveable(complexId) { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.buses_nearby), Modifier.weight(1f))
        Text(
            stringResource(R.string.station_live_age, (nowEpoch - s.fetchedAtEpochSec).coerceAtLeast(0).toInt()),
            style = MetroPulseTheme.type.caption,
            color = colors.live,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
        )
    }
    Column(Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface).animateContentSize()) {
        if (routes.isEmpty()) {
            Text(
                stringResource(R.string.buses_none, s.radiusM),
                style = MetroPulseTheme.type.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
        }
        val shown = if (all) routes else routes.take(COLLAPSED)
        shown.forEachIndexed { i, buses ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.hairline)
            BusRouteRow(buses)
        }
        if (routes.size > COLLAPSED) {
            HorizontalDivider(color = colors.hairline)
            Text(
                if (all) stringResource(R.string.buses_fewer) else stringResource(R.string.buses_more_routes, routes.size - COLLAPSED),
                style = MetroPulseTheme.type.label,
                color = colors.accent,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { all = !all }.padding(16.dp),
            )
        }
    }
    LiveCredit(stringResource(R.string.buses_credit))
}

@Composable
private fun BusRouteRow(buses: List<NearbyBus>) {
    val colors = MetroPulseTheme.colors
    val nearest = buses.first()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.widthIn(min = 64.dp).clip(MpShapes.pill).background(colors.accent.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(MpIcons.Bus, null, tint = colors.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(nearest.route?.name ?: nearest.vehicleId, style = MetroPulseTheme.type.label, color = colors.accent, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                nearest.route?.description ?: stringResource(R.string.buses_route_unnamed),
                style = MetroPulseTheme.type.body,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (buses.size > 1) {
                Text(
                    stringResource(R.string.buses_also, buses.drop(1).take(3).joinToString(", ") { distance(it.distanceM) }),
                    style = MetroPulseTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(distance(nearest.distanceM), style = MetroPulseTheme.type.bodyStrong.tabular(), color = colors.textPrimary)
            Text(
                when (nearest.motion) {
                    BusMotion.Approaching -> stringResource(R.string.buses_approaching)
                    BusMotion.Leaving -> stringResource(R.string.buses_leaving)
                    null -> stringResource(R.string.buses_seen, nearest.ageSecs)
                },
                style = MetroPulseTheme.type.caption,
                color = if (nearest.motion == BusMotion.Approaching) colors.live else colors.textSecondary,
            )
        }
    }
}

/** Rounded to 10 m: GPS on a bus is no better than that. */
private fun distance(m: Int): String = if (m < 1_000) "${(m + 5) / 10 * 10} m" else "%.1f km".format(m / 1_000.0)

private const val COLLAPSED = 6
