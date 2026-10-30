package io.github.arnavdugad.metropulse.launch

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.glance.appwidget.updateAll
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.data.city.CityDataUpdateWorker
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.di.ApplicationScope
import io.github.arnavdugad.metropulse.core.data.settings.AppSettings
import io.github.arnavdugad.metropulse.core.data.user.UserRepository
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.user.SavedJourney
import io.github.arnavdugad.metropulse.onboard.OnboardController
import io.github.arnavdugad.metropulse.widget.NextTrainsWidget
import io.github.arnavdugad.metropulse.widget.TripWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps what lives outside the app in step with it: home-screen widgets, the Quick Settings tile,
 * launcher shortcuts for favourite journeys, and the weekly city data job.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SurfaceSync @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val cities: CityRepository,
    private val user: UserRepository,
    private val settings: AppSettings,
    private val onboard: OnboardController,
    private val buildInfo: AppBuildInfo,
) {
    fun start() {
        // The trip widget and tile change with the trip, but only when something they show changes.
        scope.launch {
            onboard.session
                .map { s -> s?.progress?.let { p -> listOf(s.toName, p.phase, p.stopsToDestination, p.source, p.secondsToDestination?.div(60), (p.fraction * 50).toInt()) } }
                .distinctUntilChanged()
                .collect {
                    TripWidget().updateAll(context)
                    CommuteTileService.requestRefresh(context)
                }
        }

        val cityId = cities.state.map { (it as? CityState.Ready)?.city?.network?.cityId }.distinctUntilChanged()
        scope.launch {
            cityId.flatMapLatest { id -> if (id == null) flowOf(null) else user.favouriteStations(id) }
                .distinctUntilChanged()
                .collect { NextTrainsWidget().updateAll(context) }
        }
        scope.launch {
            cityId.flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyList())
                } else {
                    combine(user.favouriteJourneys(id), user.recentJourneys(id, limit = 4)) { favourites, recents ->
                        (favourites + recents).distinctBy { it.fromComplexId to it.toComplexId }.take(MAX_JOURNEY_SHORTCUTS)
                    }
                }
            }.distinctUntilChanged().collect(::publishShortcuts)
        }
        scope.launch {
            cityId.flatMapLatest { id -> if (id == null) flowOf(null) else settings.commute(id) }
                .distinctUntilChanged()
                .collect { CommuteTileService.requestRefresh(context) }
        }
        scope.launch {
            settings.settings.map { it.cityDataUpdates }.distinctUntilChanged().collect { on ->
                CityDataUpdateWorker.sync(WorkManager.getInstance(context), enabled = on && buildInfo.dataManifestUrl.isNotBlank())
            }
        }
    }

    private fun publishShortcuts(journeys: List<SavedJourney>) {
        val shortcuts = journeys.mapIndexed { rank, j ->
            ShortcutInfoCompat.Builder(context, "journey-${j.fromComplexId}-${j.toComplexId}")
                .setShortLabel(context.getString(R.string.shortcut_journey_short, j.toName))
                .setLongLabel(context.getString(R.string.shortcut_journey_long, j.fromName, j.toName))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_route))
                .setIntent(LaunchAction.intent(context, LaunchAction.PlanJourney(j.cityId, j.fromComplexId, j.toComplexId)))
                .setRank(rank)
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }

    private companion object {
        /** Launchers show about four shortcuts; two are left for the static ones. */
        const val MAX_JOURNEY_SHORTCUTS = 2
    }
}
