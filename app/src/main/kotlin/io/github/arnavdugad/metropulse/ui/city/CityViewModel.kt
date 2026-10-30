package io.github.arnavdugad.metropulse.ui.city

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.domain.city.CityInfo
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.map.MapFocus
import io.github.arnavdugad.metropulse.core.map.MapRoute
import io.github.arnavdugad.metropulse.core.data.user.UserRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The current city, shared by every tab (scoped to the activity). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CityViewModel @Inject constructor(private val repository: CityRepository, user: UserRepository) : ViewModel() {

    val cities: List<CityInfo> = repository.cities
    val state: StateFlow<CityState> = repository.state

    /** How often the rider uses each station of the current city, for search ranking. */
    val stationUsage: StateFlow<Map<String, Int>> = state
        .map { (it as? CityState.Ready)?.city?.info?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> id?.let(user::stationUsage) ?: flowOf(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _mapFocus = MutableStateFlow<MapFocus?>(null)

    /** A place the map should fly to next (consumed once shown). */
    val mapFocus: StateFlow<MapFocus?> = _mapFocus.asStateFlow()

    fun focusOn(focus: MapFocus) {
        _mapFocus.value = focus
    }

    fun focusShown() {
        _mapFocus.value = null
    }

    private val _mapRoute = MutableStateFlow<MapRoute?>(null)

    /** A planned journey drawn on the map ("Show on map" from a journey). */
    val mapRoute: StateFlow<MapRoute?> = _mapRoute.asStateFlow()

    fun showRoute(route: MapRoute) {
        _mapRoute.value = route
    }

    fun clearRoute() {
        _mapRoute.value = null
    }

    private val _pickerVisible = MutableStateFlow(false)
    val pickerVisible: StateFlow<Boolean> = _pickerVisible.asStateFlow()

    fun showPicker() {
        _pickerVisible.value = true
    }

    fun dismissPicker() {
        _pickerVisible.value = false
    }

    fun retry() = repository.retry()

    fun select(city: CityInfo) {
        _pickerVisible.value = false
        viewModelScope.launch { repository.select(city.id) }
    }
}
