package io.github.arnavdugad.metropulse.core.domain.city

import io.github.arnavdugad.metropulse.core.domain.fare.FareEngine
import io.github.arnavdugad.metropulse.core.domain.routing.JourneyPlanner
import io.github.arnavdugad.metropulse.core.domain.search.StationSearch
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork

/** A city's network in memory, with the services built on it (created on first use). */
class LoadedCity(val info: CityInfo?, val network: TransitNetwork) {
    val planner: JourneyPlanner by lazy { JourneyPlanner(network) }
    val search: StationSearch by lazy { StationSearch.forNetwork(network) }
    val fares: FareEngine by lazy { FareEngine(network) }
}

sealed interface CityState {
    /** The rider has not chosen a city yet. */
    data object NoCity : CityState

    /** [cityId] is null while the saved choice is still being read. */
    data class Loading(val cityId: String?) : CityState

    data class Ready(val city: LoadedCity) : CityState

    data class Failed(val cityId: String, val message: String) : CityState
}
