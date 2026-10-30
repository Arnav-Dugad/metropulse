package io.github.arnavdugad.metropulse.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.onboard.TripPhase
import io.github.arnavdugad.metropulse.core.map.MapFollow
import io.github.arnavdugad.metropulse.core.map.MetroMap
import io.github.arnavdugad.metropulse.core.map.OfflineMapState
import io.github.arnavdugad.metropulse.core.map.OfflineMaps
import io.github.arnavdugad.metropulse.ui.onboard.OnboardCard
import io.github.arnavdugad.metropulse.ui.onboard.OnboardViewModel
import io.github.arnavdugad.metropulse.ui.onboard.TripSheetHost
import io.github.arnavdugad.metropulse.launch.LaunchAction
import io.github.arnavdugad.metropulse.launch.LaunchActions
import io.github.arnavdugad.metropulse.ui.city.ChooseCity
import io.github.arnavdugad.metropulse.ui.city.CityFailed
import io.github.arnavdugad.metropulse.ui.common.LineChip
import io.github.arnavdugad.metropulse.ui.common.rememberIsOnline
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import io.github.arnavdugad.metropulse.ui.city.CityViewModel

private const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

/** Map tab: the 3D network map with the search bar and city switcher floating over it. */
@Composable
fun HomeScreen(cityViewModel: CityViewModel, onSearch: () -> Unit, onStation: (complexId: String) -> Unit) {
    val colors = MetroPulseTheme.colors
    val state by cityViewModel.state.collectAsStateWithLifecycle()
    val city = (state as? CityState.Ready)?.city
    val network = city?.network
    val onboard: OnboardViewModel = hiltViewModel()
    val session by onboard.session.collectAsStateWithLifecycle()
    val trip = session?.takeIf { it.network.cityId == network?.cityId }
    val focus by cityViewModel.mapFocus.collectAsStateWithLifecycle()
    var selectedComplex by rememberSaveable(network?.cityId) { mutableStateOf<String?>(null) }
    var trainsShown by remember { mutableStateOf(false) }
    val pending by LaunchActions.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending, network) {
        if (pending == LaunchAction.OnBoard && network != null) {
            LaunchActions.consume(LaunchAction.OnBoard)
            if (trip == null) onboard.showSheet()
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (network != null) {
            MetroMap(
                network = network,
                dark = colors.isDark,
                reducedMotion = MetroPulseTheme.motion.reduced,
                contentPadding = PaddingValues(start = 24.dp, top = 190.dp, end = 24.dp, bottom = 130.dp),
                onStationClick = { selectedComplex = it },
                onMapClick = { selectedComplex = null },
                onTrainsShown = { trainsShown = it },
                focus = focus,
                onFocusShown = cityViewModel::focusShown,
                follow = trip?.progress?.takeIf { it.phase != TripPhase.Arrived }?.let { MapFollow(it.ride.patternId, it.alongM, it.speedMps) },
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
                    .clickable(searchInteraction, indication = null, enabled = network != null, role = Role.Button, onClick = onSearch),
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
                TrainsPill(network, trainsShown)
                val online by rememberIsOnline()
                AnimatedVisibility(!online, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    OfflinePill(network.cityId, Modifier.padding(top = 8.dp))
                }
            }
        }

        when (state) {
            CityState.NoCity -> ChooseCity(cityViewModel, Modifier.align(Alignment.Center))
            is CityState.Failed -> CityFailed(cityViewModel, Modifier.align(Alignment.Center))
            is CityState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent)
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
                AnimatedVisibility(
                    visible = trip == null && selectedComplex != null,
                    enter = slideInVertically { it / 2 } + fadeIn(),
                    exit = slideOutVertically { it / 2 } + fadeOut(),
                ) {
                    val complex = selectedComplex
                    if (complex != null) StationCard(network, complex, onDetails = { onStation(complex) }, onClose = { selectedComplex = null })
                }
                if (trip == null && selectedComplex == null) {
                    Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.CenterEnd) {
                        OnBoardButton(onboard::showSheet)
                    }
                }
                Attribution()
            }
        }
    }
    TripSheetHost(onboard, city)
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

/** Says how the trains on the map are placed, so scheduled positions are never mistaken for live ones. */
@Composable
private fun TrainsPill(network: TransitNetwork, trainsShown: Boolean) {
    val colors = MetroPulseTheme.colors
    GlassSurface(shape = MpShapes.pill, elevation = 6.dp) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trainsShown) {
                val approximate = network.lines.any { it.quality == DataQuality.Approximate }
                TimeSourceChip(if (approximate) TimeSource.Approximate else TimeSource.Scheduled)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_trains_by_timetable), style = MetroPulseTheme.type.label, color = colors.textSecondary)
            } else {
                Spacer(Modifier.width(8.dp))
                // A city with a timetable may simply have no trains running now (night, or before the feed starts).
                Text(
                    stringResource(if (network.trips.isEmpty()) R.string.home_trains_none else R.string.home_trains_not_now),
                    style = MetroPulseTheme.type.label,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationCard(network: TransitNetwork, complexId: String, onDetails: () -> Unit, onClose: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val lines = network.linesAtComplex[complexId].orEmpty()
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 10.dp).sharedElement("station-$complexId"), elevation = 16.dp) {
        Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(network.complexName(complexId), style = MetroPulseTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                val closeLabel = stringResource(R.string.action_close)
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClose)
                        .semantics { contentDescription = closeLabel },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MpIcons.Close, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (line in lines) LineChip(line)
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(stringResource(R.string.home_station_details), onDetails, Modifier.fillMaxWidth().padding(end = 8.dp))
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
