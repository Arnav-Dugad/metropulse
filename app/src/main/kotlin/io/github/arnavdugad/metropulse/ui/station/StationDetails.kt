package io.github.arnavdugad.metropulse.ui.station

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.RollingText
import io.github.arnavdugad.metropulse.core.designsystem.component.SecondaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.live.Alerts
import io.github.arnavdugad.metropulse.core.domain.live.LiveDepartures
import io.github.arnavdugad.metropulse.core.domain.live.LiveGroup
import io.github.arnavdugad.metropulse.core.domain.transit.Departure
import io.github.arnavdugad.metropulse.core.domain.transit.DepartureGroup
import io.github.arnavdugad.metropulse.core.domain.transit.Departures
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.user.SavedStation
import io.github.arnavdugad.metropulse.ui.common.AlertBanners
import io.github.arnavdugad.metropulse.ui.common.LineChip
import io.github.arnavdugad.metropulse.ui.common.LineDot
import io.github.arnavdugad.metropulse.ui.common.LiveCredit
import io.github.arnavdugad.metropulse.ui.common.LiveViewModel
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.ui.common.formatDuration
import io.github.arnavdugad.metropulse.ui.common.hoursText
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZonedDateTime

/** Key of one way out of a station, for direction favourites: "line|direction|next station". */
fun DepartureGroup.directionKey(): String = "${line.id}|$direction|$nextStationId"

/**
 * Everything about one station: lines, actions, disruptions, departures (live where an operator
 * publishes them), step-free access, facilities and exits. Used by the station screen and by the
 * station sheet on the map. Pull down to refresh live times.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun StationDetails(
    complexId: String,
    network: TransitNetwork,
    favouritesViewModel: FavouriteStationsViewModel,
    liveViewModel: LiveViewModel,
    onPlanFrom: () -> Unit,
    onPlanTo: () -> Unit,
    onShowOnMap: (() -> Unit)?,
    onLine: (lineId: String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp),
    header: Boolean = true,
) {
    val colors = MetroPulseTheme.colors
    val members = network.complexes[complexId].orEmpty()
    val name = network.complexName(complexId)
    val lines = network.linesAtComplex[complexId].orEmpty()
    val favourites by favouritesViewModel.favourites.collectAsStateWithLifecycle()
    val favourite = favourites.firstOrNull { it.complexId == complexId }

    var groups by remember { mutableStateOf(emptyList<DepartureGroup>()) }
    var firstTomorrow by remember { mutableStateOf(emptyMap<String, Int>()) }
    var nowSecs by remember { mutableIntStateOf(0) }
    var nowEpoch by remember { mutableLongStateOf(Instant.now().epochSecond) }
    LaunchedEffect(network, complexId) {
        while (true) {
            val now = ZonedDateTime.now(network.timezone)
            nowSecs = now.toLocalTime().toSecondOfDay()
            nowEpoch = now.toEpochSecond()
            groups = Departures.at(network, complexId, now.toLocalDate(), nowSecs)
            firstTomorrow = Departures.at(network, complexId, now.toLocalDate().plusDays(1), 0, count = 1)
                .mapNotNull { g -> g.next.firstOrNull()?.let { g.directionKey() to it.secs } }.toMap()
            delay(REFRESH_MS)
        }
    }
    val live by remember(network, complexId) { liveViewModel.station(network, complexId) }.collectAsStateWithLifecycle(null)
    val alerts by remember(network) { liveViewModel.alerts(network) }.collectAsStateWithLifecycle(emptyList())
    val shown = remember(groups, live) {
        live?.let { LiveDepartures.merge(network, complexId, ZonedDateTime.now(network.timezone).toLocalDate(), groups, it) }
            ?: groups.map { LiveGroup(it, live = false) }
    }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(live) { refreshing = false }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            liveViewModel.refresh()
        },
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
            if (header) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .sharedElement("station-$complexId")
                        .shadow(10.dp, MpShapes.card, ambientColor = colors.shadow, spotColor = colors.shadow)
                        .clip(MpShapes.card)
                        .background(colors.surface)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Long names ("Nadaprabhu Kempegowda Station, Majestic") shrink to fit two lines.
                    BasicText(
                        name,
                        style = MetroPulseTheme.type.headline.copy(color = colors.textPrimary),
                        maxLines = 2,
                        autoSize = TextAutoSize.StepBased(minFontSize = 17.sp, maxFontSize = MetroPulseTheme.type.headline.fontSize),
                        modifier = Modifier.semantics { heading() },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        lines.forEach { line -> LineChip(line) }
                    }
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.station_plan_from), onPlanFrom, Modifier.weight(1f).fillMaxHeight())
                        SecondaryButton(stringResource(R.string.station_plan_to), onPlanTo, Modifier.weight(1f).fillMaxHeight())
                    }
                    if (onShowOnMap != null) SecondaryButton(stringResource(R.string.station_show_on_map), onShowOnMap, Modifier.fillMaxWidth(), icon = MpIcons.Map)
                }
            }

            AlertBanners(Alerts.forStation(network, alerts, complexId), Modifier.padding(top = 12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(stringResource(R.string.station_departures), Modifier.weight(1f))
                if (shown.any { it.live }) live?.let { snapshot ->
                    val age = (nowEpoch - snapshot.fetchedAtEpochSec).coerceAtLeast(0)
                    Text(
                        stringResource(R.string.station_live_age, age.toInt()),
                        style = MetroPulseTheme.type.caption,
                        color = colors.live,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                }
            }
            if (groups.isEmpty()) {
                Text(stringResource(R.string.station_no_departures), style = MetroPulseTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(4.dp))
            }
            Column(Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface)) {
                shown.forEachIndexed { i, lg ->
                    if (i > 0) HorizontalDivider(color = colors.hairline)
                    val key = lg.group.directionKey()
                    DepartureRow(
                        lg.group, nowSecs, lg.live, firstTomorrow[key],
                        pinned = favourite?.directionKey == key,
                        onClick = { onLine(lg.group.line.id) },
                        onPin = { pin ->
                            val label = "${lg.group.line.name} → ${lg.group.headsign}"
                            favouritesViewModel.setFavourite(
                                SavedStation(network.cityId, complexId, name, if (pin) key else "", if (pin) label else ""),
                                favourite = true,
                            )
                        },
                    )
                }
            }
            if (shown.any { it.live }) liveViewModel.provider(network)?.let { LiveCredit(it.credit) }

            StationAccess(network, complexId)

            val exits = network.exits.filter { e -> members.any { it.id == e.stationId } }
            if (exits.isNotEmpty()) {
                SectionTitle(stringResource(R.string.station_exits))
                Column(Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface).padding(vertical = 4.dp)) {
                    exits.sortedBy { it.name }.forEach { exit ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(MpIcons.Exit, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(exit.name.ifBlank { stringResource(R.string.station_exit_unnamed) }, style = MetroPulseTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            if (exit.wheelchair == true) Tag(stringResource(R.string.station_step_free), tint = colors.live)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Step-free status and the facilities OpenStreetMap lists at the station. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationAccess(network: TransitNetwork, complexId: String) {
    val colors = MetroPulseTheme.colors
    val stepFree = network.stepFree(complexId)
    val facilities = network.facilities(complexId)
    if (stepFree == null && facilities.isEmpty()) return
    SectionTitle(stringResource(R.string.station_facilities))
    FlowRow(
        Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (stepFree) {
            true -> Facility(MpIcons.Accessible, stringResource(R.string.station_step_free), colors.live)
            false -> Facility(MpIcons.Accessible, stringResource(R.string.station_not_step_free), colors.danger)
            null -> Unit
        }
        for (f in facilities) {
            val known: Pair<ImageVector, Int>? = when (f) {
                "toilets" -> MpIcons.Toilet to R.string.facility_toilets
                "lift" -> MpIcons.Lift to R.string.facility_lift
                "parking" -> MpIcons.Parking to R.string.facility_parking
                "atm" -> MpIcons.Atm to R.string.facility_atm
                "bicycle_parking" -> MpIcons.Bike to R.string.facility_bike
                else -> null
            }
            known?.let { (icon, label) -> Facility(icon, stringResource(label), colors.textSecondary) }
        }
    }
}

@Composable
private fun Facility(icon: ImageVector, label: String, tint: androidx.compose.ui.graphics.Color) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier.clip(MpShapes.pill).background(colors.background).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MetroPulseTheme.type.label, color = colors.textPrimary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DepartureRow(
    g: DepartureGroup,
    nowSecs: Int,
    live: Boolean,
    firstTomorrow: Int?,
    pinned: Boolean,
    onClick: () -> Unit,
    onPin: (Boolean) -> Unit,
) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                onLongClickLabel = stringResource(R.string.station_pin_direction),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineDot(g.line, 12.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.journey_towards, g.line.name, g.headsign),
                style = MetroPulseTheme.type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (pinned) Icon(MpIcons.StarFilled, stringResource(R.string.station_pinned), tint = colors.accent, modifier = Modifier.padding(end = 6.dp).size(16.dp))
            when {
                live -> TimeSourceChip(TimeSource.Live)
                g.timed -> TimeSourceChip(if (g.line.quality == DataQuality.Approximate) TimeSource.Approximate else TimeSource.Scheduled)
            }
        }
        if ((g.timed || live) && g.next.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Fixed columns keep the times aligned from one direction to the next.
                for (slot in 0 until DEPARTURE_SLOTS) {
                    val d = g.next.getOrNull(slot)
                    Column(Modifier.weight(1f)) { if (d != null) DepartureTime(d, g.headsign, nowSecs) }
                }
            }
        } else if (g.timed) {
            // Service over for today: say when it starts again rather than just "no more trains".
            val text = firstTomorrow?.let { first ->
                stringResource(R.string.station_resumes, clock(first), formatDuration(first + 86_400 - nowSecs))
            } ?: stringResource(R.string.station_service_ended)
            Text(text, style = MetroPulseTheme.type.body, color = colors.textSecondary)
        } else {
            Text(stringResource(R.string.lines_times_unavailable), style = MetroPulseTheme.type.body, color = colors.textSecondary)
        }
        val first = g.firstSecs
        val last = g.lastSecs
        if (first != null && last != null) Text(hoursText(first, last, clock), style = MetroPulseTheme.type.caption, color = colors.textTertiary)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (pinned) R.string.station_unpin_direction else R.string.station_pin_direction)) },
                leadingIcon = { Icon(if (pinned) MpIcons.Star else MpIcons.StarFilled, null) },
                onClick = { menu = false; onPin(!pinned) },
            )
        }
    }
}

@Composable
private fun DepartureTime(d: Departure, groupHeadsign: String, nowSecs: Int) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val minutes = ((d.secs - nowSecs) / 60).coerceAtLeast(0)
    when {
        minutes == 0 -> Text(stringResource(R.string.station_due), style = MetroPulseTheme.type.title, color = colors.textPrimary)
        // The minutes roll over as they count down.
        minutes < 60 -> Row(verticalAlignment = Alignment.Bottom) {
            RollingText(minutes.toString(), style = MetroPulseTheme.type.title, color = colors.textPrimary, accessibilityText = stringResource(R.string.onboard_chip_minutes, minutes))
            Text(" " + stringResource(R.string.onboard_min), style = MetroPulseTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = 2.dp))
        }
        // Countdowns past an hour are harder to read than the clock time.
        else -> Text(clock(d.secs), style = MetroPulseTheme.type.title, color = colors.textPrimary)
    }
    if (minutes < 60) Text(clock(d.secs), style = MetroPulseTheme.type.caption, color = colors.textSecondary)
    d.platform?.let { Text(it, style = MetroPulseTheme.type.caption, color = colors.textTertiary) }
    if (d.headsign != groupHeadsign) {
        Text(
            stringResource(R.string.station_short_trip, d.headsign),
            style = MetroPulseTheme.type.caption,
            color = colors.accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val REFRESH_MS = 15_000L
private const val DEPARTURE_SLOTS = 3
