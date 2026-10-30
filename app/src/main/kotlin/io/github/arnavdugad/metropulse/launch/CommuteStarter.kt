package io.github.arnavdugad.metropulse.launch

import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.settings.AppSettings
import io.github.arnavdugad.metropulse.core.data.settings.Commute
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.onboard.OnboardController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Starts on-board mode for the rider's commute, in the direction that fits the time of day. */
@Singleton
class CommuteStarter @Inject constructor(
    private val cities: CityRepository,
    private val settings: AppSettings,
    private val controller: OnboardController,
) {
    sealed interface Result {
        data class Started(val toName: String) : Result
        data object AlreadyRunning : Result
        data object NotSet : Result
        data object NoRoute : Result
        data object NoCity : Result
    }

    /** Call on the main thread; on-board mode ticks there. */
    suspend fun start(): Result {
        if (controller.session.value != null) return Result.AlreadyRunning
        val network = readyNetwork() ?: return Result.NoCity
        val commute = settings.commute(network.cityId).first() ?: return Result.NotSet
        val (from, to) = commute.leg(ZonedDateTime.now(network.timezone).hour)
        return if (controller.start(from, to, settings.current().alertStops)) Result.Started(network.complexName(to)) else Result.NoRoute
    }

    /** The commute in the current city, with the leg for now, for labels such as the tile's. */
    suspend fun describe(): Pair<String, String>? {
        val network = readyNetwork() ?: return null
        val commute: Commute = settings.commute(network.cityId).first() ?: return null
        val (from, to) = commute.leg(ZonedDateTime.now(network.timezone).hour)
        if (!network.complexes.containsKey(from) || !network.complexes.containsKey(to)) return null
        return network.complexName(from) to network.complexName(to)
    }

    private suspend fun readyNetwork(): TransitNetwork? =
        withTimeoutOrNull(CITY_WAIT_MS) { cities.state.first { it !is CityState.Loading } }
            .let { (it as? CityState.Ready)?.city?.network }

    private companion object {
        const val CITY_WAIT_MS = 10_000L
    }
}
