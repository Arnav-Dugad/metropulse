package io.github.arnavdugad.metropulse.core.domain.onboard

import io.github.arnavdugad.metropulse.core.domain.routing.ESTIMATE_DWELL_SECS
import io.github.arnavdugad.metropulse.core.domain.routing.ESTIMATE_SPEED_MPS
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.Profile
import io.github.arnavdugad.metropulse.core.domain.transit.scheduledAlong

/**
 * Moves the train along its pattern by the scheduled run times while GPS is unavailable (tunnels,
 * underground stations). The last known position is an anchor; the train is assumed to keep to
 * the schedule from there, dwelling at stations. Feed a new anchor whenever a fix is accepted.
 */
class DeadReckoner(private val pattern: Pattern, profile: Profile?) {
    private val dist = pattern.stopDistM
    private val arrivals: IntArray
    private val departures: IntArray

    init {
        if (profile != null) {
            arrivals = profile.arrivals
            departures = profile.departures
        } else {
            // No run times: estimate from distance, as for untimed journeys.
            arrivals = IntArray(pattern.size)
            departures = IntArray(pattern.size)
            for (i in 1 until pattern.size) {
                arrivals[i] = departures[i - 1] + ((dist[i] - dist[i - 1]) / ESTIMATE_SPEED_MPS).toInt()
                departures[i] = arrivals[i] + if (i < pattern.size - 1) ESTIMATE_DWELL_SECS else 0
            }
        }
    }

    /** Scheduled offset (seconds from the first departure) at which the train is at [alongM]. */
    fun scheduleOffset(alongM: Double): Double {
        if (alongM <= dist[0]) return departures[0].toDouble()
        for (i in 0 until pattern.size - 1) {
            if (alongM < dist[i + 1]) {
                val f = (alongM - dist[i]) / (dist[i + 1] - dist[i]).coerceAtLeast(1e-6)
                return departures[i] + f * (arrivals[i + 1] - departures[i])
            }
        }
        return arrivals.last().toDouble()
    }

    /** Position along the pattern at scheduled offset [offsetSecs]. */
    fun positionAt(offsetSecs: Double): Double = scheduledAlong(dist, arrivals, departures, offsetSecs)

    /** Estimated position [elapsedSecs] after the train was at [anchorAlongM]. */
    fun advance(anchorAlongM: Double, elapsedSecs: Double): Double =
        positionAt(scheduleOffset(anchorAlongM) + elapsedSecs.coerceAtLeast(0.0))

    /** Scheduled seconds from [alongM] until arrival at stop [stopIndex] (0 if already past it). */
    fun secondsToStop(alongM: Double, stopIndex: Int): Double =
        (arrivals[stopIndex] - scheduleOffset(alongM)).coerceAtLeast(0.0)

    /** Index of the next stop ahead of [alongM] (within [toleranceM] counts as at that stop). */
    fun nextStopIndex(alongM: Double, toleranceM: Double = 30.0): Int {
        for (i in 0 until pattern.size) if (dist[i] + toleranceM >= alongM) return i
        return pattern.size - 1
    }
}
