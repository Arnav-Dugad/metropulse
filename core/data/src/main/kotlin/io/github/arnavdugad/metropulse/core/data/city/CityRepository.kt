package io.github.arnavdugad.metropulse.core.data.city

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.core.data.city.db.CityDatabase
import io.github.arnavdugad.metropulse.core.data.di.ApplicationScope
import io.github.arnavdugad.metropulse.core.domain.city.CityInfo
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The rider's chosen city and its network. The whole network is read into memory once (a few
 * hundred KB) and the database is closed again, so a data update can replace the file at any
 * time; the network is then reloaded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CityRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: CityFiles,
    private val store: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
) {
    /** Cities this build can show, by name. */
    val cities: List<CityInfo> get() = files.bundled.sortedBy { it.name }

    val selectedCityId: Flow<String?> = store.data.map { it[SELECTED_CITY] }.distinctUntilChanged()

    /** Bumped by [retry] so a city that failed to load is read again. */
    private val attempt = MutableStateFlow(0)

    val state: StateFlow<CityState> = combine(selectedCityId, attempt) { id, _ -> id }
        .flatMapLatest { id ->
            if (id == null) {
                flowOf<CityState>(CityState.NoCity)
            } else {
                flow<CityState> {
                    emit(CityState.Loading(id))
                    emit(load(id))
                    files.replaced.filter { it == id }.collect { emit(load(id)) }
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, CityState.Loading(null))

    fun retry() {
        attempt.value++
    }

    /** Installs the city's bundled data if needed, then makes it the current city. */
    suspend fun select(cityId: String) {
        files.ensureInstalled(cityId)
        store.edit { it[SELECTED_CITY] = cityId }
    }

    private suspend fun load(cityId: String): CityState = withContext(Dispatchers.IO) {
        try {
            CityState.Ready(LoadedCity(files.bundled.firstOrNull { it.id == cityId }, read(files.ensureInstalled(cityId))))
        } catch (e: CancellationException) {
            throw e
        } catch (first: Exception) {
            // A damaged or outdated file: reinstall the bundled copy once.
            try {
                files.lock.withLock { files.file(cityId).delete() }
                CityState.Ready(LoadedCity(files.bundled.firstOrNull { it.id == cityId }, read(files.ensureInstalled(cityId))))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CityState.Failed(cityId, e.message ?: first.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun read(file: File) = files.lock.withLock {
        val db = CityDatabase.open(context, file)
        try {
            CityNetworkLoader.load(db.dao())
        } finally {
            db.close()
        }
    }

    private companion object {
        val SELECTED_CITY = stringPreferencesKey("selected_city")
    }
}
