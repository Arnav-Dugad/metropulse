package io.github.arnavdugad.metropulse.onboard

import android.content.Context
import android.location.Location
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.di.ApplicationScope
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.onboard.GpsFix
import io.github.arnavdugad.metropulse.core.domain.onboard.TripPhase
import io.github.arnavdugad.metropulse.core.domain.onboard.TripProgress
import io.github.arnavdugad.metropulse.core.domain.onboard.TripTracker
import io.github.arnavdugad.metropulse.core.domain.routing.Journey
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** A trip in progress, as the UI and notifications see it. */
data class TripSession(
    val network: TransitNetwork,
    val fromName: String,
    val toName: String,
    val journey: Journey,
    val alertStops: Int,
    val progress: TripProgress,
    /** The wake alert has been raised (once per trip). */
    val alerted: Boolean,
)

/**
 * Runs on-board mode: plans the trip, feeds GPS fixes and clock ticks to the [TripTracker], raises
 * the wake alert, and keeps an exact alarm armed as a backup in case the process is stopped.
 */
@Singleton
class OnboardController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cities: CityRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _session = MutableStateFlow<TripSession?>(null)
    val session: StateFlow<TripSession?> = _session.asStateFlow()

    private var tracker: TripTracker? = null
    private var backupAlarmAt: Long? = null
    private var ticker: Job? = null

    /** Plans [fromComplexId] to [toComplexId] leaving now and starts tracking. False if no route. */
    fun start(fromComplexId: String, toComplexId: String, alertStops: Int): Boolean {
        val city = (cities.state.value as? CityState.Ready)?.city ?: return false
        val network = city.network
        val now = ZonedDateTime.now(network.timezone)
        val journey = city.planner.plan(fromComplexId, toComplexId, now.toLocalDate(), now.toLocalTime().toSecondOfDay(), departures = 1)
            .journeys.firstOrNull() ?: return false
        val t = TripTracker(network, journey, alertStops)
        tracker = t
        backupAlarmAt = null
        _session.value = TripSession(
            network = network,
            fromName = network.complexName(fromComplexId),
            toName = network.complexName(toComplexId),
            journey = journey,
            alertStops = alertStops,
            progress = t.onTick(System.currentTimeMillis()),
            alerted = false,
        )
        // Ticks run on the main thread, like location callbacks, so the tracker is never shared across threads.
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                tick()
                delay(TICK_MS)
            }
        }
        OnboardService.start(context)
        return true
    }

    fun stop() {
        ticker?.cancel()
        ticker = null
        tracker = null
        _session.value = null
        WakeAlert.cancelBackup(context)
        WakeAlert.dismiss(context)
        OnboardService.stop(context)
    }

    fun onLocation(location: Location) {
        val t = tracker ?: return
        publish(t.onFix(GpsFix(location.time, LatLon(location.latitude, location.longitude), location.accuracy.toDouble())))
    }

    private fun tick(nowMillis: Long = System.currentTimeMillis()) {
        val t = tracker ?: return
        publish(t.onTick(nowMillis))
    }

    /** How often to ask for GPS fixes: often near stations and before the alert, rarely mid-segment. */
    fun desiredIntervalMs(): Long {
        val p = _session.value?.progress ?: return SLOW_MS
        return when {
            p.phase == TripPhase.WaitingToBoard -> FAST_MS
            p.phase == TripPhase.Arrived -> SLOW_MS
            p.stopsToAlight <= 2 -> FAST_MS // approaching a change or the destination
            else -> {
                val pattern = _session.value!!.network.patternById.getValue(p.ride.patternId)
                val toNext = pattern.stopDistM[p.nextStopIndex] - p.alongM
                if (toNext < NEAR_STOP_M) FAST_MS else CRUISE_MS
            }
        }
    }

    private fun publish(p: TripProgress) {
        val s = _session.value ?: return
        var alerted = s.alerted
        if (p.alertDue && !alerted) {
            alerted = true
            WakeAlert.cancelBackup(context)
            WakeAlert.show(context, s.toName)
        }
        _session.value = s.copy(progress = p, alerted = alerted)
        if (!alerted && p.phase != TripPhase.Arrived) armBackup(s.toName, p)
    }

    /** An exact alarm shortly before the expected arrival, rescheduled only when the estimate moves. */
    private fun armBackup(toName: String, p: TripProgress) {
        val secs = p.secondsToDestination ?: return
        val at = System.currentTimeMillis() + (secs - BACKUP_LEAD_SECS).coerceAtLeast(30) * 1000L
        val previous = backupAlarmAt
        if (previous == null || abs(previous - at) > RESCHEDULE_MS) {
            backupAlarmAt = at
            WakeAlert.scheduleBackup(context, at, toName)
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val FAST_MS = 1_000L
        const val CRUISE_MS = 3_000L
        const val SLOW_MS = 10_000L
        const val NEAR_STOP_M = 500.0
        const val BACKUP_LEAD_SECS = 60
        const val RESCHEDULE_MS = 30_000L
    }
}
