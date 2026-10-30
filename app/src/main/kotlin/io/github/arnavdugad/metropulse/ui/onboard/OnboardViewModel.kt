package io.github.arnavdugad.metropulse.ui.onboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.core.data.settings.AppSettings
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import io.github.arnavdugad.metropulse.core.domain.routing.Journey
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.onboard.OnboardController
import io.github.arnavdugad.metropulse.onboard.TripSession
import io.github.arnavdugad.metropulse.ui.common.NearbyStation
import io.github.arnavdugad.metropulse.ui.common.NearbyStations
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZonedDateTime
import javax.inject.Inject

@HiltViewModel
class OnboardViewModel @Inject constructor(
    private val controller: OnboardController,
    private val nearbyStations: NearbyStations,
    settings: AppSettings,
) : ViewModel() {

    /** The wake alert setting new trips start with. */
    val defaultAlertStops: StateFlow<Int> = settings.settings.map { it.alertStops }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)

    val session: StateFlow<TripSession?> = controller.session

    private val _sheetVisible = MutableStateFlow(false)
    val sheetVisible: StateFlow<Boolean> = _sheetVisible.asStateFlow()

    /** The closest station, if one is within walking distance. */
    val nearby: StateFlow<NearbyStation?> = nearbyStations.nearby.map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun showSheet() {
        _sheetVisible.value = true
    }

    fun dismissSheet() {
        _sheetVisible.value = false
        nearbyStations.stop()
    }

    /** The journey the trip would follow if started now (for the sheet's preview). */
    fun preview(city: LoadedCity, from: String, to: String): Journey? {
        val now = ZonedDateTime.now(city.network.timezone)
        return city.planner.plan(from, to, now.toLocalDate(), now.toLocalTime().toSecondOfDay(), departures = 1).journeys.firstOrNull()
    }

    fun start(from: String, to: String, alertStops: Int): Boolean {
        val started = controller.start(from, to, alertStops)
        if (started) dismissSheet()
        return started
    }

    fun stop() = controller.stop()

    fun findNearby(network: TransitNetwork) = nearbyStations.locate(network)

    override fun onCleared() = nearbyStations.stop()
}
