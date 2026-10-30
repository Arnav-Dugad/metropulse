package io.github.arnavdugad.metropulse.ui.common

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.core.data.live.LiveProvider
import io.github.arnavdugad.metropulse.core.data.live.LiveRepository
import io.github.arnavdugad.metropulse.core.data.settings.AppSettings
import io.github.arnavdugad.metropulse.core.domain.live.LiveSnapshot
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import javax.inject.Inject

/**
 * Live predictions for whatever screen collects them, refreshed every [REFRESH_MS] while
 * collected (TfL caches its answers for 30 s). A failed refresh keeps the last snapshot for a
 * couple of minutes, then falls back to the timetable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LiveViewModel @Inject constructor(private val repository: LiveRepository, private val settings: AppSettings) : ViewModel() {

    fun provider(network: TransitNetwork): LiveProvider? = repository.provider(network)

    fun station(network: TransitNetwork, complexId: String): Flow<LiveSnapshot?> = poll(network) { repository.forStation(network, complexId) }

    fun line(network: TransitNetwork, lineId: String): Flow<LiveSnapshot?> = poll(network) { repository.forLine(network, lineId) }

    private fun poll(network: TransitNetwork, fetch: suspend () -> LiveSnapshot?): Flow<LiveSnapshot?> =
        settings.settings.map { it.liveData && repository.provider(network) != null }.distinctUntilChanged().flatMapLatest { on ->
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
                        delay(REFRESH_MS)
                    }
                }
            }
        }

    private companion object {
        const val REFRESH_MS = 30_000L
        const val KEEP_SECS = 120
    }
}
