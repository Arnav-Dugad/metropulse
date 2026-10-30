package io.github.arnavdugad.metropulse.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.github.arnavdugad.metropulse.launch.CommuteStarter
import io.github.arnavdugad.metropulse.launch.LaunchAction
import io.github.arnavdugad.metropulse.launch.LaunchActions
import io.github.arnavdugad.metropulse.ui.onboarding.OnboardingScreen
import io.github.arnavdugad.metropulse.ui.settings.AboutScreen
import io.github.arnavdugad.metropulse.ui.settings.TripsScreen
import io.github.arnavdugad.metropulse.ui.settings.SettingsViewModel
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.toRoute
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.map.MapFocus
import io.github.arnavdugad.metropulse.ui.common.LocalStationUsage
import io.github.arnavdugad.metropulse.ui.common.LocalNavAnimatedScope
import io.github.arnavdugad.metropulse.ui.common.LocalSharedTransitionScope
import io.github.arnavdugad.metropulse.ui.lines.LineScreen
import io.github.arnavdugad.metropulse.ui.plan.JourneyScreen
import io.github.arnavdugad.metropulse.ui.plan.PlanViewModel
import io.github.arnavdugad.metropulse.ui.search.SearchScreen
import io.github.arnavdugad.metropulse.ui.station.FavouriteStationsViewModel
import io.github.arnavdugad.metropulse.ui.station.StationScreen
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.GlassSurface
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.designsystem.theme.scaledAtMost
import io.github.arnavdugad.metropulse.ui.city.CityPickerHost
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import io.github.arnavdugad.metropulse.ui.home.HomeActions
import io.github.arnavdugad.metropulse.ui.home.HomeScreen
import io.github.arnavdugad.metropulse.ui.lines.LinesScreen
import io.github.arnavdugad.metropulse.ui.plan.PlanScreen
import io.github.arnavdugad.metropulse.ui.settings.SettingsScreen
import io.github.arnavdugad.metropulse.update.UpdateSheetHost
import io.github.arnavdugad.metropulse.update.UpdateViewModel
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object MapRoute
@Serializable data object PlanRoute
@Serializable data object LinesRoute
@Serializable data object SettingsRoute
@Serializable data object SearchRoute
@Serializable data class StationRoute(val complexId: String)
@Serializable data class LineRoute(val lineId: String)
@Serializable data class JourneyRoute(val index: Int)
@Serializable data object AboutRoute
@Serializable data object TripsRoute

private data class Tab(val route: Any, val routeClass: KClass<*>, val icon: ImageVector, val label: Int)

private val tabs = listOf(
    Tab(MapRoute, MapRoute::class, MpIcons.Map, R.string.nav_map),
    Tab(PlanRoute, PlanRoute::class, MpIcons.Route, R.string.nav_plan),
    Tab(LinesRoute, LinesRoute::class, MpIcons.Lines, R.string.nav_lines),
    Tab(SettingsRoute, SettingsRoute::class, MpIcons.Sliders, R.string.nav_settings),
)

/** First run shows onboarding; afterwards the tabs. Settings are loaded before this is shown. */
@Composable
fun MetroPulseRoot(updateViewModel: UpdateViewModel, settingsViewModel: SettingsViewModel) {
    val cityViewModel: CityViewModel = hiltViewModel()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val onboarded = settings?.onboardingDone ?: return
    AnimatedContent(onboarded, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) }, label = "onboarding") { done ->
        if (done) {
            MainContent(updateViewModel, settingsViewModel, cityViewModel)
        } else {
            OnboardingScreen(cityViewModel, onDone = settingsViewModel::finishOnboarding)
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun MainContent(updateViewModel: UpdateViewModel, settingsViewModel: SettingsViewModel, cityViewModel: CityViewModel) {
    val planViewModel: PlanViewModel = hiltViewModel()
    val favouriteStations: FavouriteStationsViewModel = hiltViewModel()
    val navController = rememberNavController()
    val hazeState = rememberHazeState()
    val backStack by navController.currentBackStackEntryAsState()
    val tabIndex = tabs.indexOfFirst { tab -> backStack?.destination?.hasRoute(tab.routeClass) == true }
    val selectedIndex = tabIndex.coerceAtLeast(0)
    val cityState by cityViewModel.state.collectAsStateWithLifecycle()
    val city = (cityState as? CityState.Ready)?.city

    fun goToTab(route: Any) = navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    // From a detail screen: drop the details without saving them (restoring them later would bring
    // them back over the tab), then switch tabs.
    fun leaveTo(route: Any) {
        navController.popBackStack(navController.graph.findStartDestination().id, inclusive = false)
        if (route != MapRoute) goToTab(route)
    }
    val openStation: (String) -> Unit = { navController.navigate(StationRoute(it)) }
    val openLine: (String) -> Unit = { navController.navigate(LineRoute(it)) }

    // Shortcuts, widgets and the Quick Settings tile open the app with an action to carry out.
    val context = LocalContext.current
    val pending by LaunchActions.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending, city?.network?.cityId) {
        val action = pending ?: return@LaunchedEffect
        when (action) {
            LaunchAction.Search -> { leaveTo(MapRoute); navController.navigate(SearchRoute) }
            LaunchAction.Plan -> leaveTo(PlanRoute)
            LaunchAction.Settings -> leaveTo(SettingsRoute)
            LaunchAction.OnBoard -> { leaveTo(MapRoute); return@LaunchedEffect } // the map opens the trip sheet
            LaunchAction.StartCommute -> {
                leaveTo(MapRoute)
                val message = when (settingsViewModel.startCommute()) {
                    CommuteStarter.Result.NotSet -> { leaveTo(SettingsRoute); R.string.commute_not_set }
                    CommuteStarter.Result.NoRoute -> R.string.onboard_no_route
                    else -> null
                }
                message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
            }
            is LaunchAction.PlanJourney -> {
                val network = city?.network ?: return@LaunchedEffect
                if (action.cityId == network.cityId) {
                    planViewModel.setFrom(action.fromComplexId)
                    planViewModel.setTo(action.toComplexId)
                    leaveTo(PlanRoute)
                }
            }
            is LaunchAction.Station -> {
                val network = city?.network ?: return@LaunchedEffect
                if (action.cityId == network.cityId && network.complexes.containsKey(action.complexId)) {
                    leaveTo(MapRoute)
                    openStation(action.complexId)
                }
            }
        }
        LaunchActions.consume(action)
    }

    Box(Modifier.fillMaxSize().background(MetroPulseTheme.colors.background)) {
        SharedTransitionLayout {
            val stationUsage by cityViewModel.stationUsage.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalStationUsage provides stationUsage) {
                NavHost(
                    navController = navController,
                    startDestination = MapRoute,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                ) {
                    destination<MapRoute> {
                        HomeScreen(
                            cityViewModel, favouriteStations,
                            HomeActions(
                                onSearch = { navController.navigate(SearchRoute) },
                                onLines = { goToTab(LinesRoute) },
                                onLine = openLine,
                                onPlanFrom = { planViewModel.setFrom(it); goToTab(PlanRoute) },
                                onPlanTo = { planViewModel.setTo(it); goToTab(PlanRoute) },
                            ),
                        )
                    }
                    destination<PlanRoute> { PlanScreen(planViewModel, cityViewModel, onOpenJourney = { navController.navigate(JourneyRoute(it)) }) }
                    destination<LinesRoute> { LinesScreen(cityViewModel, onLine = openLine) }
                    destination<SettingsRoute> { SettingsScreen(updateViewModel, cityViewModel, settingsViewModel, onAbout = { navController.navigate(AboutRoute) }, onTrips = { navController.navigate(TripsRoute) }) }
                    destination<TripsRoute> { TripsScreen(city?.network, hiltViewModel(), onBack = { navController.popBackStack() }) }
                    destination<AboutRoute> { AboutScreen(city?.network, updateViewModel.installedVersion, onBack = { navController.popBackStack() }) }
                    destination<SearchRoute> {
                        SearchScreen(city, hiltViewModel(), favouriteStations, onBack = { navController.popBackStack() }) { id ->
                            navController.navigate(StationRoute(id)) { popUpTo<SearchRoute> { inclusive = true } }
                        }
                    }
                    destination<StationRoute> { entry ->
                        val complexId = entry.toRoute<StationRoute>().complexId
                        StationScreen(
                            complexId = complexId,
                            network = city?.network,
                            favouritesViewModel = favouriteStations,
                            onBack = { navController.popBackStack() },
                            onPlanFrom = { planViewModel.setFrom(complexId); leaveTo(PlanRoute) },
                            onPlanTo = { planViewModel.setTo(complexId); leaveTo(PlanRoute) },
                            onShowOnMap = {
                                city?.network?.complexes?.get(complexId)?.first()?.location?.let { cityViewModel.focusOn(MapFocus(it.lat, it.lon)) }
                                leaveTo(MapRoute)
                            },
                            onLine = openLine,
                        )
                    }
                    destination<LineRoute> { entry ->
                        LineScreen(entry.toRoute<LineRoute>().lineId, city?.network, onBack = { navController.popBackStack() }, onStation = openStation)
                    }
                    destination<JourneyRoute> { entry ->
                        JourneyScreen(
                            index = entry.toRoute<JourneyRoute>().index,
                            planViewModel = planViewModel,
                            cityViewModel = cityViewModel,
                            onBack = { navController.popBackStack() },
                            onStation = openStation,
                            onStarted = { leaveTo(MapRoute) },
                            onShowOnMap = { leaveTo(MapRoute) },
                        )
                    }
                }
            }
        }
        // The tab bar belongs to the four tabs; detail screens get the whole height.
        AnimatedVisibility(
            visible = tabIndex >= 0,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            FloatingTabBar(
                selectedIndex = selectedIndex,
                hazeState = hazeState,
                onSelect = { index -> goToTab(tabs[index].route) },
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
    UpdateSheetHost(updateViewModel)
    CityPickerHost(cityViewModel)
}

/** A destination that provides its animation scope for shared-element transitions. */
private inline fun <reified T : Any> NavGraphBuilder.destination(crossinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavAnimatedScope provides this) { content(entry) }
    }
}

/** Floating frosted tab bar; the highlight pill springs between tabs. */
@Composable
private fun FloatingTabBar(
    selectedIndex: Int,
    hazeState: HazeState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    GlassSurface(modifier.height(68.dp), hazeState = hazeState, shape = MpShapes.pill) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(6.dp)) {
            val slot = maxWidth / tabs.size
            val indicatorX by animateDpAsState(slot * selectedIndex, MetroPulseTheme.motion.bouncy(), label = "tabIndicator")
            Box(
                Modifier
                    .offset(x = indicatorX)
                    .width(slot)
                    .fillMaxHeight()
                    .clip(MpShapes.pill)
                    .background(colors.accentSoft),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    val tint = if (selected) colors.accent else colors.textSecondary
                    Column(
                        Modifier
                            .width(slot)
                            .fillMaxHeight()
                            .clip(MpShapes.pill)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                                if (!selected) {
                                    haptics.tick()
                                    onSelect(index)
                                }
                            }
                            .semantics { this.selected = selected },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Text(stringResource(tab.label), style = MetroPulseTheme.type.caption.scaledAtMost(1.3f), color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}
