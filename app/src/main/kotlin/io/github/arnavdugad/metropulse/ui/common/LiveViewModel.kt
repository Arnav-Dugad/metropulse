package io.github.arnavdugad.metropulse.ui.common

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.core.data.live.DelhiBusRepository
import io.github.arnavdugad.metropulse.core.data.live.LiveProvider
import io.github.arnavdugad.metropulse.core.data.live.LiveRepository
import io.github.arnavdugad.metropulse.core.data.settings.AppSettings
import io.github.arnavdugad.metropulse.core.domain.live.BusSnapshot
import io.github.arnavdugad.metropulse.core.domain.live.LiveSnapshot
import io.github.arnavdugad.metropulse.core.domain.live.ServiceAlert
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject

/**
 * Live predictions and disruptions for whatever screen collects them, refreshed every [REFRESH_MS]
 * while collected (TfL caches its answers for 30 s) or at once on [refresh]. A failed refresh keeps
 * the last snapshot for a couple of minutes, then falls back to the timetable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: LiveRepository,
    private val buses: DelhiBusRepository,
    private val settings: AppSettings,
) : ViewModel() {

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun provider(network: TransitNetwork): LiveProvider? = repository.provider(network)

    /** Fetch again now (pull to refresh). */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    fun station(network: TransitNetwork, complexId: String): Flow<LiveSnapshot?> = poll(network) { repository.forStation(network, complexId) }

    fun line(network: TransitNetwork, lineId: String): Flow<LiveSnapshot?> = poll(network) { repository.forLine(network, lineId) }

    fun lines(network: TransitNetwork, lineIds: Set<String>): Flow<LiveSnapshot?> =
        if (lineIds.isEmpty()) flowOf(null) else poll(network) { repository.forLines(network, lineIds) }

    fun stations(network: TransitNetwork, complexIds: Set<String>): Flow<LiveSnapshot?> =
        if (complexIds.isEmpty()) flowOf(null) else poll(network) { repository.forStations(network, complexIds) }

    /** Live buses near a station, where the city publishes bus positions (Delhi); null elsewhere or when off. */
    fun buses(network: TransitNetwork, complexId: String): Flow<BusSnapshot?> =
        settings.settings.map { it.liveData && buses.available(network) }.distinctUntilChanged().flatMapLatest { on ->
            if (!on) flowOf(null) else flow {
                var last: BusSnapshot? = null
                while (true) {
                    last = try {
                        buses.near(network, complexId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        last?.takeIf { Instant.now().epochSecond - it.fetchedAtEpochSec < KEEP_SECS }
                    }
                    emit(last)
                    withTimeoutOrNull(REFRESH_MS) { refreshes.first() }
                }
            }
        }

    /** Current disruptions, rechecked every couple of minutes; empty where no operator publishes them. */
    fun alerts(network: TransitNetwork): Flow<List<ServiceAlert>> = enabled(network).flatMapLatest { on ->
        if (!on) flowOf(emptyList()) else flow {
            while (true) {
                emit(runCatching { repository.alerts(network) }.getOrDefault(emptyList()))
                withTimeoutOrNull(ALERTS_MS) { refreshes.first() }
            }
        }
    }

    private fun enabled(network: TransitNetwork): Flow<Boolean> =
        settings.settings.map { it.liveData && repository.provider(network) != null }.distinctUntilChanged()

    private fun poll(network: TransitNetwork, fetch: suspend () -> LiveSnapshot?): Flow<LiveSnapshot?> =
        enabled(network).flatMapLatest { on ->
            if (!on) {
                flowOf(null)
            } else {
                flow {
                    var last: LiveSnapshot? = null
                    while (true) {
                        last = try {
                            fetch()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            last?.takeIf { Instant.now().epochSecond - it.fetchedAtEpochSec < KEEP_SECS }
                        }
                        emit(last)
                        withTimeoutOrNull(REFRESH_MS) { refreshes.first() }
                    }
                }
            }
        }

    private companion object {
        const val REFRESH_MS = 30_000L
        const val ALERTS_MS = 120_000L
        const val KEEP_SECS = 120
    }
}
