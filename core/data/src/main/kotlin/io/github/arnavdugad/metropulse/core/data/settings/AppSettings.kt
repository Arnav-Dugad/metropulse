package io.github.arnavdugad.metropulse.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { System, Light, Dark }

data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    val highContrast: Boolean = false,
    /** On top of the system's "remove animations"; either one reduces motion. */
    val reduceMotion: Boolean = false,
    /** Default wake alert for new trips: this many stops before the destination. */
    val alertStops: Int = 1,
    val cityDataUpdates: Boolean = true,
    /** Live arrival predictions where an operator publishes them freely (London). */
    val liveData: Boolean = true,
    val onboardingDone: Boolean = false,
)

/** The rider's two commute stations in one city. Which way to go is decided by the time of day. */
data class Commute(val cityId: String, val homeComplexId: String, val workComplexId: String) {
    /** Home to work before noon, work to home after. */
    fun leg(hour: Int): Pair<String, String> = if (hour < 12) homeComplexId to workComplexId else workComplexId to homeComplexId
}

/** App preferences, kept on the device in the shared settings DataStore. */
@Singleton
class AppSettings @Inject constructor(private val store: DataStore<Preferences>) {

    val settings: Flow<Settings> = store.data.map { p ->
        Settings(
            theme = p[THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.System,
            highContrast = p[HIGH_CONTRAST] ?: false,
            reduceMotion = p[REDUCE_MOTION] ?: false,
            alertStops = (p[ALERT_STOPS] ?: 1).coerceIn(1, 2),
            cityDataUpdates = p[CITY_DATA_UPDATES] ?: true,
            liveData = p[LIVE_DATA] ?: true,
            onboardingDone = p[ONBOARDING_DONE] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun setTheme(mode: ThemeMode) = store.edit { it[THEME] = mode.name }
    suspend fun setHighContrast(on: Boolean) = store.edit { it[HIGH_CONTRAST] = on }
    suspend fun setReduceMotion(on: Boolean) = store.edit { it[REDUCE_MOTION] = on }
    suspend fun setAlertStops(stops: Int) = store.edit { it[ALERT_STOPS] = stops.coerceIn(1, 2) }
    suspend fun setCityDataUpdates(on: Boolean) = store.edit { it[CITY_DATA_UPDATES] = on }
    suspend fun setLiveData(on: Boolean) = store.edit { it[LIVE_DATA] = on }
    suspend fun setOnboardingDone() = store.edit { it[ONBOARDING_DONE] = true }

    fun commute(cityId: String): Flow<Commute?> = store.data.map { p ->
        val home = p[stringPreferencesKey(HOME_PREFIX + cityId)]
        val work = p[stringPreferencesKey(WORK_PREFIX + cityId)]
        if (home != null && work != null && home != work) Commute(cityId, home, work) else null
    }.distinctUntilChanged()

    /** Home or work station for [cityId]; null clears it. */
    suspend fun setCommuteStation(cityId: String, home: Boolean, complexId: String?) = store.edit {
        val key = stringPreferencesKey((if (home) HOME_PREFIX else WORK_PREFIX) + cityId)
        if (complexId == null) it.remove(key) else it[key] = complexId
    }

    fun commuteStations(cityId: String): Flow<Pair<String?, String?>> = store.data.map { p ->
        p[stringPreferencesKey(HOME_PREFIX + cityId)] to p[stringPreferencesKey(WORK_PREFIX + cityId)]
    }.distinctUntilChanged()

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val ALERT_STOPS = intPreferencesKey("alert_stops")
        val CITY_DATA_UPDATES = booleanPreferencesKey("city_data_updates")
        val LIVE_DATA = booleanPreferencesKey("live_data")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        const val HOME_PREFIX = "commute_home_"
        const val WORK_PREFIX = "commute_work_"
    }
}
