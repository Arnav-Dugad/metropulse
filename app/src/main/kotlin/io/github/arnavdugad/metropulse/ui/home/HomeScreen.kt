package io.github.arnavdugad.metropulse.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.GlassSurface
import io.github.arnavdugad.metropulse.core.designsystem.component.SecondaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.component.pressScale
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.designsystem.theme.scaledAtMost
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.onboard.TripPhase
import io.github.arnavdugad.metropulse.core.domain.transit.ScheduledTrains
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.user.SavedStation
import io.github.arnavdugad.metropulse.core.map.MapFollow
import io.github.arnavdugad.metropulse.core.map.MetroMap
import io.github.arnavdugad.metropulse.core.map.OfflineMapState
import io.github.arnavdugad.metropulse.core.map.OfflineMaps
import io.github.arnavdugad.metropulse.core.map.TrainSource
import io.github.arnavdugad.metropulse.launch.LaunchAction
import io.github.arnavdugad.metropulse.launch.LaunchActions
import io.github.arnavdugad.metropulse.ui.city.ChooseCity
import io.github.arnavdugad.metropulse.ui.city.CityFailed
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import io.github.arnavdugad.metropulse.ui.common.LineChip
import io.github.arnavdugad.metropulse.ui.common.LiveViewModel
import io.github.arnavdugad.metropulse.ui.common.SkeletonList
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.rememberIsOnline
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import io.github.arnavdugad.metropulse.ui.common.tint
import io.github.arnavdugad.metropulse.ui.onboard.OnboardCard
import io.github.arnavdugad.metropulse.ui.onboard.OnboardViewModel
import io.github.arnavdugad.metropulse.ui.onboard.TripSheetHost
import io.github.arnavdugad.metropulse.ui.station.FavouriteStationsViewModel
import io.github.arnavdugad.metropulse.ui.station.StationDetails
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

private const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

/** What the map tab can open elsewhere in the app. */
class HomeActions(
    val onSearch: () -> Unit,
    val onLines: () -> Unit,
    val onLine: (lineId: String) -> Unit,
    val onPlanFrom: (complexId: String) -> Unit,
    val onPlanTo: (complexId: String) -> Unit,
)

/**
 * Map tab: the 3D network map with the search bar and city switcher floating over it. Tapping a
 * station opens a card; "Departures and details" grows it into a sheet over the map.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(cityViewModel: CityViewModel, favouriteStations: FavouriteStationsViewModel, actions: HomeActions) {
    val colors = MetroPulseTheme.colors
    val haptics = LocalHapticFeedback.current
    val state by cityViewModel.state.collectAsStateWithLifecycle()
    val city = (state as? CityState.Ready)?.city
    val network = city?.network
    val onboard: OnboardViewModel = hiltViewModel()
    val liveViewModel: LiveViewModel = hiltViewModel()
    val session by onboard.session.collectAsStateWithLifecycle()
    val tripSheet by onboard.sheetVisible.collectAsStateWithLifecycle()
    val cityPicker by cityViewModel.pickerVisible.collectAsStateWithLifecycle()
    val trip = session?.takeIf { it.network.cityId == network?.cityId }
    val focus by cityViewModel.mapFocus.collectAsStateWithLifecycle()
    val route by cityViewModel.mapRoute.collectAsStateWithLifecycle()
    var selectedComplex by rememberSaveable(network?.cityId) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(network?.cityId) { mutableStateOf(false) }
    var source by remember { mutableStateOf(TrainSource.None) }
    val pending by LaunchActions.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending, network) {
        if (pending == LaunchAction.OnBoard && network != null) {
            LaunchActions.consume(LaunchAction.OnBoard)
            if (trip == null) onboard.showSheet()
        }
    }

    // Live trains for the lines in view; the camera reports them when it settles, debounced so a
    // pan across the city does not fire a request per frame.
    var wantedLines by remember { mutableStateOf(emptySet<String>()) }
    var liveLines by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(wantedLines) {
        delay(LIVE_DEBOUNCE_MS)
        liveLines = wantedLines
    }
    val live by remember(network, liveLines) { network?.let { liveViewModel.lines(it, liveLines) } ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsStateWithLifecycle(null)
    val alerts by remember(network) { network?.let(liveViewModel::alerts) ?: kotlinx.coroutines.flow.flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())

    BackHandler(enabled = expanded) { expanded = false }
    BackHandler(enabled = !expanded && selectedComplex != null) { selectedComplex = null }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (network != null) {
            MetroMap(
                network = network,
                dark = colors.isDark,
                reducedMotion = MetroPulseTheme.motion.reduced,
                contentPadding = PaddingValues(start = 24.dp, top = 190.dp, end = 24.dp, bottom = 130.dp),
                onStationClick = { selectedComplex = it; expanded = false },
                onMapClick = { selectedComplex = null; expanded = false },
                onTrains = { source = it },
                focus = focus,
                onFocusShown = cityViewModel::focusShown,
                follow = trip?.progress?.takeIf { it.phase != TripPhase.Arrived }?.let { MapFollow(it.ride.patternId, it.alongM, it.speedMps) },
                live = live,
                onLiveLinesWanted = { wantedLines = it },
                onStationLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    selectedComplex = it
                    expanded = false
                },
                route = route,
                paused = cityPicker || tripSheet || expanded,
                modifier = Modifier.fillMaxSize(),
            )
            // Keeps the header legible over busy map areas.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to colors.background.copy(alpha = 0.97f),
                            0.55f to colors.background.copy(alpha = 0.8f),
                            1f to colors.background.copy(alpha = 0f),
                        ),
                    ),
            )
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(colors.accentSoft.copy(alpha = if (colors.isDark) 0.5f else 0.9f), colors.background))),
            )
        }

        Column(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name), style = MetroPulseTheme.type.display.scaledAtMost(1.15f), color = colors.textPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                if (network != null) CityPill(network.cityName, cityViewModel::showPicker)
            }
            Spacer(Modifier.size(16.dp))
            val searchInteraction = remember { MutableInteractionSource() }
            GlassSurface(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .pressScale(searchInteraction, 0.98f)
                    .clip(MpShapes.pill)
                    .clickable(searchInteraction, indication = null, enabled = network != null, role = Role.Button, onClick = actions.onSearch),
                shape = MpShapes.pill,
                elevation = 10.dp,
            ) {
                Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(MpIcons.Search, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.home_where_to), style = MetroPulseTheme.type.bodyStrong, color = colors.textSecondary)
                }
            }
            if (network != null) {
                Spacer(Modifier.size(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TrainsPill(network, source)
                    // Lines with a disruption (station notices are many and shown on their stations).
                    val disrupted = alerts.filter { it.stationIds.isEmpty() }
                    val lines = disrupted.flatMap { it.lineIds }.toSet().size
                    if (lines > 0) AlertsPill(lines, disrupted.minOf { it.severity }.tint(), actions.onLines)
                }
                val online by rememberIsOnline()
                AnimatedVisibility(!online, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    OfflinePill(network.cityId, Modifier.padding(top = 8.dp))
                }
                AnimatedVisibility(route != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    RouteChip(cityViewModel::clearRoute, Modifier.padding(top = 8.dp))
                }
            }
        }

        when (state) {
            CityState.NoCity -> ChooseCity(cityViewModel, Modifier.align(Alignment.Center))
            is CityState.Failed -> CityFailed(cityViewModel, Modifier.align(Alignment.Center))
            is CityState.Loading -> SkeletonList(
                stringResource(R.string.city_loading),
                rows = 3,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 110.dp),
            )
            else -> Unit
        }

        if (network != null) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 92.dp, start = 16.dp, end = 16.dp),
            ) {
                AnimatedVisibility(
                    visible = trip != null,
                    enter = slideInVertically { it / 2 } + fadeIn(),
                    exit = slideOutVertically { it / 2 } + fadeOut(),
                ) {
                    trip?.let { OnboardCard(it, onEnd = onboard::stop, modifier = Modifier.padding(bottom = 10.dp)) }
                }
                if (trip == null && selectedComplex == null) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.CenterEnd) {
                        OnBoardButton(onboard::showSheet)
                    }
                }
                if (!expanded) Attribution()
            }
            // The station card, which grows into a sheet over the map.
            AnimatedVisibility(
                visible = trip == null && selectedComplex != null,
                enter = slideInVertically { it / 2 } + fadeIn(),
                exit = slideOutVertically { it / 2 } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val complex = selectedComplex
                if (complex != null) {
                    StationSheet(
                        network, complex, expanded, favouriteStations, liveViewModel, actions,
                        onExpand = { expanded = it },
                        onClose = { selectedComplex = null; expanded = false },
                    )
                }
            }
        }
    }
    TripSheetHost(onboard, city)
}

/**
 * A station on the map: a compact card with the lines and quick actions; expanded, a sheet with
 * live departures, disruptions and facilities. Drag the handle up to open it, down to close it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationSheet(
    network: TransitNetwork,
    complexId: String,
    expanded: Boolean,
    favourites: FavouriteStationsViewModel,
    liveViewModel: LiveViewModel,
    actions: HomeActions,
    onExpand: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val colors = MetroPulseTheme.colors
    val density = LocalDensity.current
    val saved by favourites.favourites.collectAsStateWithLifecycle()
    val favourite = saved.any { it.complexId == complexId }
    val name = network.complexName(complexId)
    var drag by remember { mutableFloatStateOf(0f) }
    val threshold = with(density) { 56.dp.toPx() }
    val dragState = rememberDraggableState { drag += it }
    BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding()) {
        val sheetHeight = maxHeight * 0.7f
        AnimatedContent(
            expanded,
            transitionSpec = { (fadeIn() + expandVertically(expandFrom = Alignment.Bottom)) togetherWith fadeOut() using SizeTransform(clip = false) },
            label = "stationSheet",
        ) { open ->
            GlassSurface(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = if (open) 8.dp else 102.dp)
                    .then(if (open) Modifier.height(sheetHeight) else Modifier)
                    .sharedElement("station-$complexId")
                    // Open, the sheet is read like a page: an opaque surface. Closed, the glass is nearly
                    // opaque too, so map labels never show through the station's name and buttons.
                    .clip(MpShapes.card)
                    .background(if (open) colors.surface else colors.surface.copy(alpha = 0.93f))
                    // The card owns its touches: drags open or close it, taps never reach the map below.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .draggable(
                        dragState, Orientation.Vertical,
                        onDragStarted = { drag = 0f },
                        onDragStopped = {
                            when {
                                drag < -threshold && !open -> onExpand(true)
                                drag > threshold && open -> onExpand(false)
                                drag > threshold -> onClose()
                            }
                        },
                    ),
                elevation = 16.dp,
            ) {
                Column {
                    // Handle row: drag up to open, down to close.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 8.dp, top = 8.dp),
                    ) {
                        Box(
                            Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(MpShapes.pill)
                                .background(colors.textTertiary.copy(alpha = 0.4f)),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Text(
                                name, style = MetroPulseTheme.type.title, color = colors.textPrimary, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            SheetIcon(
                                if (favourite) MpIcons.StarFilled else MpIcons.Star,
                                stringResource(if (favourite) R.string.action_unsave else R.string.action_save),
                                if (favourite) colors.accent else colors.textSecondary,
                            ) { favourites.setFavourite(SavedStation(network.cityId, complexId, name), !favourite) }
                            if (open) SheetIcon(MpIcons.ChevronDown, stringResource(R.string.home_station_collapse), colors.textSecondary) { onExpand(false) }
                            SheetIcon(MpIcons.Close, stringResource(R.string.action_close), colors.textSecondary, onClose)
                        }
                    }
                    if (open) {
                        StationDetails(
                            complexId, network, favourites, liveViewModel,
                            onPlanFrom = { actions.onPlanFrom(complexId) },
                            onPlanTo = { actions.onPlanTo(complexId) },
                            onShowOnMap = null,
                            onLine = actions.onLine,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp),
                            header = false,
                        )
                    } else {
                        Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (line in network.linesAtComplex[complexId].orEmpty()) LineChip(line)
                                if (network.stepFree(complexId) == true) {
                                    Icon(MpIcons.Accessible, stringResource(R.string.station_step_free), tint = colors.live, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SecondaryButton(stringResource(R.string.station_plan_from), { actions.onPlanFrom(complexId) }, Modifier.weight(1f).fillMaxHeight())
                                SecondaryButton(stringResource(R.string.station_plan_to), { actions.onPlanTo(complexId) }, Modifier.weight(1f).fillMaxHeight())
                            }
                            Spacer(Modifier.height(8.dp))
                            SecondaryButton(stringResource(R.string.home_station_expand), { onExpand(true) }, Modifier.fillMaxWidth(), icon = MpIcons.Clock)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun OnBoardButton(onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction)
            .shadow(14.dp, MpShapes.pill, ambientColor = colors.accent, spotColor = colors.accent)
            .clip(MpShapes.pill)
            .background(colors.tripBrush)
            .clickable(interaction, indication = null, role = Role.Button) { haptics.tick(); onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MpIcons.Train, null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.home_on_board), style = MetroPulseTheme.type.bodyStrong, color = colors.onAccent)
    }
}

/**
 * Says where the trains on the map come from, so scheduled positions are never mistaken for live
 * ones; with none running, when service starts again.
 */
@Composable
private fun TrainsPill(network: TransitNetwork, source: TrainSource) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    GlassSurface(shape = MpShapes.pill, elevation = 6.dp) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (source) {
                TrainSource.Live -> {
                    TimeSourceChip(TimeSource.Live)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_trains_live), style = MetroPulseTheme.type.label, color = colors.textSecondary)
                }
                TrainSource.Scheduled -> {
                    val approximate = network.lines.any { it.quality == DataQuality.Approximate }
                    TimeSourceChip(if (approximate) TimeSource.Approximate else TimeSource.Scheduled)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_trains_by_timetable), style = MetroPulseTheme.type.label, color = colors.textSecondary)
                }
                TrainSource.None -> {
                    Spacer(Modifier.width(8.dp))
                    val next = remember(network) {
                        val now = ZonedDateTime.now(network.timezone)
                        ScheduledTrains.nextDeparture(network, now.toLocalDate(), now.toLocalTime().toSecondOfDay())
                    }
                    Text(
                        when {
                            network.trips.isEmpty() -> stringResource(R.string.home_trains_none)
                            next != null -> stringResource(R.string.home_service_resumes, clock(next.second))
                            else -> stringResource(R.string.home_trains_not_now)
                        },
                        style = MetroPulseTheme.type.label,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}

/** "3 disruptions now", in the colour of the worst; opens the lines. */
@Composable
private fun AlertsPill(count: Int, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    GlassSurface(Modifier.clip(MpShapes.pill).clickable(role = Role.Button, onClick = onClick), shape = MpShapes.pill, elevation = 6.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MpIcons.Alert, null, tint = tint, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(pluralStringResource(R.plurals.alerts_count, count, count), style = MetroPulseTheme.type.label, color = colors.textPrimary)
        }
    }
}

@Composable
private fun RouteChip(onClear: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    GlassSurface(modifier.clip(MpShapes.pill).clickable(role = Role.Button, onClick = onClear), shape = MpShapes.pill, elevation = 6.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MpIcons.Route, null, tint = colors.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.home_route_clear), style = MetroPulseTheme.type.label, color = colors.textPrimary)
            Spacer(Modifier.width(4.dp))
            Icon(MpIcons.Close, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
        }
    }
}

/** No connection: lines, stations and trains still work; map details depend on a downloaded map. */
@Composable
private fun OfflinePill(cityId: String, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val context = LocalContext.current
    LaunchedEffect(Unit) { OfflineMaps.refresh(context) }
    val states by OfflineMaps.states.collectAsStateWithLifecycle()
    val downloaded = states[cityId] is OfflineMapState.Ready
    GlassSurface(modifier, shape = MpShapes.pill, elevation = 6.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MpIcons.Offline, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (downloaded) R.string.home_offline_downloaded else R.string.home_offline),
                style = MetroPulseTheme.type.label,
                color = colors.textSecondary,
            )
        }
    }
}

/** OpenFreeMap and OpenStreetMap require visible attribution; tapping it opens OSM's copyright page. */
@Composable
private fun Attribution(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val colors = MetroPulseTheme.colors
    Text(
        stringResource(R.string.map_attribution),
        style = MetroPulseTheme.type.caption,
        color = colors.textSecondary,
        modifier = modifier
            .clip(MpShapes.pill)
            .background(colors.background.copy(alpha = 0.7f))
            .clickable(role = Role.Button) { uriHandler.openUri(OSM_COPYRIGHT_URL) }
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun CityPill(name: String, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction)
            .clip(MpShapes.pill)
            .background(colors.surface.copy(alpha = 0.85f))
            .clickable(interaction, indication = null, role = Role.Button) { haptics.tick(); onClick() }
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MpIcons.Pin, null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(name, style = MetroPulseTheme.type.label, color = colors.textPrimary)
        Spacer(Modifier.width(2.dp))
        Icon(MpIcons.ChevronDown, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
    }
}

private const val LIVE_DEBOUNCE_MS = 1_200L
