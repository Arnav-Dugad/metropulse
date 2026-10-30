package io.github.arnavdugad.metropulse.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.user.UserRepository
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import io.github.arnavdugad.metropulse.core.domain.fare.FareQuote
import io.github.arnavdugad.metropulse.core.domain.routing.Journey
import io.github.arnavdugad.metropulse.core.domain.user.SavedJourney
import io.github.arnavdugad.metropulse.onboard.OnboardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import javax.inject.Inject

/** What the rider asked for. [departAt] null means "leave now". */
data class PlanQuery(val from: String? = null, val to: String? = null, val departAt: LocalTime? = null)

enum class OptionTag { Fastest, FewestChanges }

data class PlanOption(val journey: Journey, val fare: FareQuote?, val tags: Set<OptionTag>)

sealed interface PlanResults {
    data object Idle : PlanResults
    data object Planning : PlanResults
    data class Ready(val date: LocalDate, val departSecs: Int, val options: List<PlanOption>, val untimedLines: Set<String>) : PlanResults
    data object NoRoute : PlanResults
}

/**
 * The journey planner's state, shared by the Plan tab and the journey screen (scoped to the
 * activity). Plans whenever both ends are set or the time changes, and keeps the recents.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val cities: CityRepository,
    private val user: UserRepository,
    private val onboard: OnboardController,
) : ViewModel() {

    private val _query = MutableStateFlow(PlanQuery())
    val query: StateFlow<PlanQuery> = _query.asStateFlow()

    private val _results = MutableStateFlow<PlanResults>(PlanResults.Idle)
    val results: StateFlow<PlanResults> = _results.asStateFlow()

    private val cityId = cities.state.map { (it as? CityState.Ready)?.city?.network?.cityId }

    val favourites: StateFlow<List<SavedJourney>> = cityId.flatMapLatest { id -> id?.let(user::favouriteJourneys) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recents: StateFlow<List<SavedJourney>> = cityId.flatMapLatest { id -> id?.let { user.recentJourneys(it, 8) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // A new city clears the planner.
        viewModelScope.launch {
            var last: String? = null
            cityId.collect { id ->
                if (id != last) {
                    last = id
                    _query.value = PlanQuery()
                    _results.value = PlanResults.Idle
                }
            }
        }
        viewModelScope.launch {
            combine(_query, cities.state) { q, s -> q to (s as? CityState.Ready)?.city }.collect { (q, city) -> plan(q, city) }
        }
    }

    fun setFrom(complexId: String) = _query.update { it.copy(from = complexId) }
    fun setTo(complexId: String) = _query.update { it.copy(to = complexId) }
    fun swap() = _query.update { it.copy(from = it.to, to = it.from) }
    fun setDepartAt(time: LocalTime?) = _query.update { it.copy(departAt = time) }

    fun open(saved: SavedJourney) {
        _query.value = PlanQuery(saved.fromComplexId, saved.toComplexId)
    }

    fun setFavourite(from: String, to: String, favourite: Boolean) {
        val network = currentCity()?.network ?: return
        viewModelScope.launch {
            user.setFavouriteJourney(SavedJourney(network.cityId, from, network.complexName(from), to, network.complexName(to)), favourite)
        }
    }

    /** Starts on-board mode for this pair of stations (leaving now). */
    fun startOnBoard(from: String, to: String): Boolean = onboard.start(from, to, alertStops = 1)

    private fun currentCity(): LoadedCity? = (cities.state.value as? CityState.Ready)?.city

    private suspend fun plan(q: PlanQuery, city: LoadedCity?) {
        val from = q.from
        val to = q.to
        if (city == null || from == null || to == null || from == to) {
            _results.value = PlanResults.Idle
            return
        }
        _results.value = PlanResults.Planning
        val network = city.network
        val now = ZonedDateTime.now(network.timezone)
        val date = if (q.departAt != null && q.departAt < now.toLocalTime().minusMinutes(1)) now.toLocalDate().plusDays(1) else now.toLocalDate()
        val departSecs = (q.departAt ?: now.toLocalTime()).toSecondOfDay()
        val result = withContext(Dispatchers.Default) {
            val plan = city.planner.plan(from, to, date, departSecs, departures = 3)
            val fastest = plan.journeys.filter { it.timed }.minByOrNull { it.arriveSecs!! } ?: plan.journeys.minByOrNull { it.durationSecs }
            val fewest = plan.journeys.minOfOrNull { it.transfers }
            val options = plan.journeys.map { j ->
                val tags = buildSet {
                    if (j === fastest) add(OptionTag.Fastest)
                    if (j.transfers == fewest && j !== fastest && plan.journeys.firstOrNull { it.transfers == fewest } === j) add(OptionTag.FewestChanges)
                }
                PlanOption(j, city.fares.quote(j, date, j.departSecs ?: departSecs), tags)
            }
            if (options.isEmpty()) PlanResults.NoRoute else PlanResults.Ready(date, departSecs, options, plan.untimedLines)
        }
        _results.value = result
        if (result is PlanResults.Ready) {
            user.recordJourney(network.cityId, from, network.complexName(from), to, network.complexName(to))
        }
    }
}
