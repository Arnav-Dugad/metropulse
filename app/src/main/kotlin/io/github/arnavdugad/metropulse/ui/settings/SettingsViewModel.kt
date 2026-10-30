package io.github.arnavdugad.metropulse.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.data.city.CityDataUpdater
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.settings.AppSettings
import io.github.arnavdugad.metropulse.core.data.settings.Settings
import io.github.arnavdugad.metropulse.core.data.settings.ThemeMode
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.launch.CommuteStarter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.reminders.CommuteReminder
import javax.inject.Inject

/** Result of the last manual city data check. */
sealed interface DataCheck {
    data object Idle : DataCheck
    data object Checking : DataCheck
    data object UpToDate : DataCheck
    data class Updated(val count: Int) : DataCheck
    data object Failed : DataCheck
}

/** App preferences (activity-scoped: the theme and onboarding read it too). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AppSettings,
    private val cities: CityRepository,
    private val updater: CityDataUpdater,
    private val commute: CommuteStarter,
    buildInfo: AppBuildInfo,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Null until read from disk, so the splash screen can wait for it. */
    val state: StateFlow<Settings?> = settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val cityId = cities.state.map { (it as? CityState.Ready)?.city?.network?.cityId }.distinctUntilChanged()

    /** Home and work stations in the current city. */
    val commuteStations: StateFlow<Pair<String?, String?>> = cityId
        .flatMapLatest { id -> if (id == null) flowOf(null to null) else settings.commuteStations(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

    val cityDataAvailable = buildInfo.dataManifestUrl.isNotBlank()

    private val _dataCheck = MutableStateFlow<DataCheck>(DataCheck.Idle)
    val dataCheck: StateFlow<DataCheck> = _dataCheck.asStateFlow()

    fun setTheme(mode: ThemeMode) = edit { settings.setTheme(mode) }
    fun setHighContrast(on: Boolean) = edit { settings.setHighContrast(on) }
    fun setReduceMotion(on: Boolean) = edit { settings.setReduceMotion(on) }
    fun setAlertStops(stops: Int) = edit { settings.setAlertStops(stops) }
    fun setCityDataUpdates(on: Boolean) = edit { settings.setCityDataUpdates(on) }
    fun setLiveData(on: Boolean) = edit { settings.setLiveData(on) }
    fun setVibrateAtStops(on: Boolean) = edit { settings.setVibrateAtStops(on) }
    fun setStepFreeRoutes(on: Boolean) = edit { settings.setStepFreeRoutes(on) }

    /** Saves the weekday reminder and (re)schedules or cancels its alarm. */
    fun setCommuteReminder(on: Boolean, minutes: Int) = edit {
        settings.setCommuteReminder(on, minutes)
        CommuteReminder.sync(context)
    }
    fun finishOnboarding() = edit { settings.setOnboardingDone() }

    fun setCommuteStation(home: Boolean, complexId: String?) {
        val id = (cities.state.value as? CityState.Ready)?.city?.network?.cityId ?: return
        edit { settings.setCommuteStation(id, home, complexId) }
    }

    suspend fun startCommute(): CommuteStarter.Result = commute.start()

    fun checkCityData() {
        if (_dataCheck.value == DataCheck.Checking) return
        _dataCheck.value = DataCheck.Checking
        viewModelScope.launch {
            _dataCheck.value = try {
                val updated = updater.updateAll()
                if (updated.isEmpty()) DataCheck.UpToDate else DataCheck.Updated(updated.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DataCheck.Failed
            }
        }
    }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
