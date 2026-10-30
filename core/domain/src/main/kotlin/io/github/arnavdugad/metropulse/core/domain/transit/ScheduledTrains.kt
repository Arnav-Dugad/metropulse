package io.github.arnavdugad.metropulse.core.domain.transit

import java.time.LocalDate

/** A train's position by the timetable at one moment. */
data class TrainPosition(
    val tripId: Int,
    val patternId: Int,
    val lineId: String,
    /** Metres along the pattern's shape. */
    val alongM: Double,
    /** Dwelling at a station rather than running. */
    val atStation: Boolean,
)

/**
 * Where each timetabled train should be on [date], according to the schedule. These are
 * *scheduled* positions, not live ones (Indian metros publish no live data); the map labels them so.
 */
class ScheduledTrains(private val network: TransitNetwork, private val date: LocalDate) {

    companion object {
        /**
         * When the next train leaves anywhere on the network after [nowSecs] on [date]: today, or
         * the first of tomorrow. Null if nothing runs on either day.
         */
        fun nextDeparture(network: TransitNetwork, date: LocalDate, nowSecs: Int): Pair<LocalDate, Int>? {
            for ((day, after) in listOf(date to nowSecs, date.plusDays(1) to 0)) {
                val running = network.services.filter { it.runsOn(day) }.mapTo(HashSet()) { it.id }
                val first = network.trips.asSequence().filter { it.serviceId in running && it.startSecs >= after }.minOfOrNull { it.startSecs }
                if (first != null) return day to first
            }
            return null
        }
    }


    private class Run(val trip: Trip, val pattern: Pattern, val profile: Profile, val startSecs: Int) {
        val endSecs = startSecs + profile.arrivals.last()
    }

    private val runs: List<Run> = buildList {
        for ((day, offset) in listOf(date.minusDays(1) to -86_400, date to 0)) {
            val running = network.services.filter { it.runsOn(day) }.mapTo(HashSet()) { it.id }
            for (trip in network.trips) {
                if (trip.serviceId !in running) continue
                val pattern = network.patternById[trip.patternId] ?: continue
                val profile = network.profileById[trip.profileId] ?: continue
                val start = trip.startSecs + offset
                if (start + profile.arrivals.last() >= 0) add(Run(trip, pattern, profile, start))
            }
        }
    }.sortedBy { it.startSecs }

    /** Trains running at [secs] seconds after [date]'s midnight (fractions give smooth motion). */
    fun positionsAt(secs: Double): List<TrainPosition> {
        val out = ArrayList<TrainPosition>()
        for (run in runs) {
            if (run.startSecs > secs) break
            if (secs > run.endSecs) continue
            val offset = secs - run.startSecs
            val along = scheduledAlong(run.pattern.stopDistM, run.profile.arrivals, run.profile.departures, offset)
            val dwelling = dwellingAt(run.profile.arrivals, run.profile.departures, offset)
            out += TrainPosition(run.trip.id, run.pattern.id, run.pattern.lineId, along, dwelling)
        }
        return out
    }

    val isEmpty: Boolean get() = runs.isEmpty()
}

/**
 * Position along a pattern [offsetSecs] after its first departure, given per-stop arrival and
 * departure offsets: constant speed between stops, stationary while dwelling.
 */
internal fun scheduledAlong(dist: DoubleArray, arrivals: IntArray, departures: IntArray, offsetSecs: Double): Double {
    if (offsetSecs <= departures[0]) return dist[0]
    for (i in 0 until dist.size - 1) {
        val a = arrivals[i + 1]
        if (offsetSecs < a) {
            val f = (offsetSecs - departures[i]) / (a - departures[i]).coerceAtLeast(1)
            return dist[i] + f * (dist[i + 1] - dist[i])
        }
        if (offsetSecs < departures[i + 1]) return dist[i + 1]
    }
    return dist.last()
}

private fun dwellingAt(arrivals: IntArray, departures: IntArray, offsetSecs: Double): Boolean =
    arrivals.indices.any { offsetSecs >= arrivals[it] && offsetSecs <= departures[it] }
