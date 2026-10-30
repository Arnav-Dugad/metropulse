package io.github.arnavdugad.metropulse.ui.station

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.user.UserRepository
import io.github.arnavdugad.metropulse.core.designsystem.component.SecondaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.transit.Departure
import io.github.arnavdugad.metropulse.core.domain.transit.DepartureGroup
import io.github.arnavdugad.metropulse.core.domain.live.LiveDepartures
import io.github.arnavdugad.metropulse.core.domain.live.LiveGroup
import io.github.arnavdugad.metropulse.core.domain.transit.Departures
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.user.SavedStation
import io.github.arnavdugad.metropulse.ui.common.DetailTopBar
import io.github.arnavdugad.metropulse.ui.common.LiveCredit
import io.github.arnavdugad.metropulse.ui.common.LiveViewModel
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.LineChip
import io.github.arnavdugad.metropulse.ui.common.LineDot
import io.github.arnavdugad.metropulse.ui.common.RoundIconButton
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.ui.common.hoursText
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject

/** The rider's favourite stations in the current city. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavouriteStationsViewModel @Inject constructor(cities: CityRepository, private val user: UserRepository) : ViewModel() {
    val favourites: StateFlow<List<SavedStation>> = cities.state
        .map { (it as? CityState.Ready)?.city?.network?.cityId }
        .flatMapLatest { id -> id?.let(user::favouriteStations) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFavourite(station: SavedStation, favourite: Boolean) {
        viewModelScope.launch { user.setFavouriteStation(station, favourite) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StationScreen(
    complexId: String,
    network: TransitNetwork?,
    favouritesViewModel: FavouriteStationsViewModel,
    onBack: () -> Unit,
    onPlanFrom: () -> Unit,
    onPlanTo: () -> Unit,
    onShowOnMap: () -> Unit,
    onLine: (lineId: String) -> Unit,
    liveViewModel: LiveViewModel = hiltViewModel(),
) {
    val colors = MetroPulseTheme.colors
    val favourites by favouritesViewModel.favourites.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(colors.background)) {
        val members = network?.complexes?.get(complexId)
        if (network == null || members == null) {
            Column {
                DetailTopBar(onBack)
                EmptyState(MpIcons.Pin, stringResource(R.string.station_gone_title), stringResource(R.string.station_gone_body), Modifier.padding(top = 80.dp))
            }
            return@Box
        }
        val name = network.complexName(complexId)
        val lines = network.linesAtComplex[complexId].orEmpty()
        val favourite = favourites.any { it.complexId == complexId }
        // Departures refresh as time passes.
        var groups by remember { mutableStateOf(emptyList<DepartureGroup>()) }
        // Live predictions (London) replace the timetable where they match; elsewhere the timetable stays.
        val live by remember(network, complexId) { liveViewModel.station(network, complexId) }.collectAsStateWithLifecycle(null)
        val shown = remember(groups, live) {
            live?.let { LiveDepartures.merge(network, complexId, ZonedDateTime.now(network.timezone).toLocalDate(), groups, it) }
                ?: groups.map { LiveGroup(it, live = false) }
        }
        var nowSecs by remember { mutableStateOf(0) }
        LaunchedEffect(network, complexId) {
            while (true) {
                val now = ZonedDateTime.now(network.timezone)
                nowSecs = now.toLocalTime().toSecondOfDay()
                groups = Departures.at(network, complexId, now.toLocalDate(), nowSecs)
                delay(REFRESH_MS)
            }
        }
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(onBack) {
                RoundIconButton(
                    if (favourite) MpIcons.StarFilled else MpIcons.Star,
                    stringResource(if (favourite) R.string.action_unsave else R.string.action_save),
                    { favouritesViewModel.setFavourite(SavedStation(network.cityId, complexId, name), !favourite) },
                    if (favourite) colors.accent else colors.textPrimary,
                )
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
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
                    Text(name, style = MetroPulseTheme.type.headline, color = colors.textPrimary, modifier = Modifier.semantics { heading() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        lines.forEach { line -> LineChip(line) }
                    }
                    // Equal heights even when large text wraps one label and not the other.
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.station_plan_from), onPlanFrom, Modifier.weight(1f).fillMaxHeight())
                        SecondaryButton(stringResource(R.string.station_plan_to), onPlanTo, Modifier.weight(1f).fillMaxHeight())
                    }
                    SecondaryButton(stringResource(R.string.station_show_on_map), onShowOnMap, Modifier.fillMaxWidth(), icon = MpIcons.Map)
                }

                SectionTitle(stringResource(R.string.station_departures))
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.station_no_departures), style = MetroPulseTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(4.dp))
                }
                Column(Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface)) {
                    shown.forEachIndexed { i, lg ->
                        if (i > 0) HorizontalDivider(color = colors.hairline)
                        DepartureRow(lg.group, nowSecs, lg.live, onClick = { onLine(lg.group.line.id) })
                    }
                }
                if (shown.any { it.live }) liveViewModel.provider(network)?.let { LiveCredit(it.credit) }

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
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DepartureRow(g: DepartureGroup, nowSecs: Int, live: Boolean, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
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
            Text(stringResource(R.string.station_service_ended), style = MetroPulseTheme.type.body, color = colors.textSecondary)
        } else {
            Text(stringResource(R.string.lines_times_unavailable), style = MetroPulseTheme.type.body, color = colors.textSecondary)
        }
        val first = g.firstSecs
        val last = g.lastSecs
        if (first != null && last != null) {
            val hours = hoursText(first, last, clock)
            Text(hours, style = MetroPulseTheme.type.caption, color = colors.textTertiary)
        }
    }
}

@Composable
private fun DepartureTime(d: Departure, groupHeadsign: String, nowSecs: Int) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val minutes = ((d.secs - nowSecs) / 60).coerceAtLeast(0)
    // Countdowns past an hour are harder to read than the clock time.
    Text(
        when {
            minutes == 0 -> stringResource(R.string.station_due)
            minutes < 60 -> stringResource(R.string.onboard_chip_minutes, minutes)
            else -> clock(d.secs)
        },
        style = MetroPulseTheme.type.title,
        color = colors.textPrimary,
    )
    if (minutes < 60) Text(clock(d.secs), style = MetroPulseTheme.type.caption, color = colors.textSecondary)
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
