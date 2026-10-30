package io.github.arnavdugad.metropulse.core.domain.live

import io.github.arnavdugad.metropulse.core.domain.transit.Departure
import io.github.arnavdugad.metropulse.core.domain.transit.DepartureGroup
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.TrainPosition
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import java.time.LocalDate

/**
 * One predicted arrival from an operator's live feed, already matched to this network's ids.
 * [nextStationId] (where the train goes after [stationId]) or [destinationStationId] tells which
 * way it runs; feeds give one or the other.
 */
data class LiveArrival(
    val lineId: String,
    val stationId: String,
    /** Predicted arrival, epoch seconds. */
    val epochSec: Long,
    /** Identifies the train across stations (a trip or vehicle id), when the feed has one. */
    val vehicle: String? = null,
    val destinationStationId: String? = null,
    val nextStationId: String? = null,
)

/** Predictions fetched at [fetchedAtEpochSec]; MetroPulse labels times from them "Live". */
data class LiveSnapshot(val arrivals: List<LiveArrival>, val fetchedAtEpochSec: Long)

object LiveDepartures {

    /**
     * Replaces the timetable's next departures with live predictions in every group a prediction
     * matches (same line, same way out of the station). Groups without predictions keep their
     * timetable times and stay labelled as such.
     */
    fun merge(network: TransitNetwork, complexId: String, date: LocalDate, groups: List<DepartureGroup>, live: LiveSnapshot, count: Int = 3): List<LiveGroup> {
        val members = network.complexes[complexId].orEmpty().mapTo(HashSet()) { it.id }
        val midnight = date.atStartOfDay(network.timezone).toEpochSecond()
        val byGroup = HashMap<DepartureGroup, MutableList<Departure>>()
        for (a in live.arrivals) {
            if (a.stationId !in members || a.epochSec < live.fetchedAtEpochSec - STALE_GRACE_SECS) continue
            val group = groups.firstOrNull { g -> g.line.id == a.lineId && matches(network, g, a) } ?: continue
            val headsign = a.destinationStationId?.let { network.stationById[it]?.name } ?: group.headsign
            byGroup.getOrPut(group) { ArrayList() } += Departure((a.epochSec - midnight).toInt(), headsign)
        }
        return groups.map { g ->
            val live = byGroup[g]?.sortedBy { it.secs }?.distinctBy { it.secs }?.take(count)
            if (live.isNullOrEmpty()) LiveGroup(g, live = false) else LiveGroup(g.copy(next = live), live = true)
        }
    }

    /** Does arrival [a] leave the station the way group [g] does (same direction, same next stop)? */
    private fun matches(network: TransitNetwork, g: DepartureGroup, a: LiveArrival): Boolean {
        if (a.nextStationId != null) return sameComplex(network, a.nextStationId, g.nextStationId)
        val destination = a.destinationStationId ?: return false
        return network.patterns.any { p ->
            if (p.lineId != g.line.id || p.direction != g.direction) return@any false
            val i = p.stationIds.indexOfFirst { sameComplex(network, it, a.stationId) }
            i >= 0 && i + 1 < p.size && sameComplex(network, p.stationIds[i + 1], g.nextStationId) &&
                p.stationIds.drop(i + 1).any { sameComplex(network, it, destination) }
        }
    }

    private fun sameComplex(network: TransitNetwork, a: String, b: String): Boolean =
        a == b || network.stationById[a]?.complexId?.let { it == network.stationById[b]?.complexId } == true

    /** Predictions this far in the past are still shown as "due" for a moment. */
    private const val STALE_GRACE_SECS = 30
}

/** A departure group and whether its times come from a live feed. */
data class LiveGroup(val group: DepartureGroup, val live: Boolean)

object LivePositions {

    /**
     * Where each predicted train is now: between the stop before its next stop and that stop,
     * placed by how long the timetable takes for that hop. Trains are identified by [LiveArrival.vehicle];
     * predictions without one cannot be followed and are skipped.
     */
    fun estimate(network: TransitNetwork, live: LiveSnapshot, nowEpochSec: Long, lineIds: Set<String>): List<TrainPosition> {
        val out = ArrayList<TrainPosition>()
        val byVehicle = live.arrivals.filter { it.vehicle != null && it.lineId in lineIds }.groupBy { it.lineId to it.vehicle!! }
        for ((key, arrivals) in byVehicle) {
            val next = arrivals.minByOrNull { it.epochSec } ?: continue
            val pattern = patternFor(network, next) ?: continue
            val i = pattern.stationIds.indexOfFirst { sameComplex(network, it, next.stationId) }
            if (i <= 0) continue
            val profile = network.profiles.firstOrNull { it.patternId == pattern.id }
            val hop = profile?.let { (it.arrivals[i] - it.departures[i - 1]).coerceAtLeast(30) } ?: DEFAULT_HOP_SECS
            val eta = (next.epochSec - nowEpochSec).coerceAtLeast(0)
            val fraction = (1.0 - eta.toDouble() / hop).coerceIn(0.0, 1.0)
            val from = pattern.stopDistM[i - 1]
            val to = pattern.stopDistM[i]
            out += TrainPosition(key.second.hashCode(), pattern.id, pattern.lineId, from + (to - from) * fraction, atStation = eta == 0L)
        }
        return out
    }

    /** A pattern of the train's line that reaches its next stop, heading where the feed says it goes. */
    private fun patternFor(network: TransitNetwork, a: LiveArrival): Pattern? =
        network.patterns.filter { it.lineId == a.lineId }.firstOrNull { p ->
            val i = p.stationIds.indexOfFirst { sameComplex(network, it, a.stationId) }
            if (i < 0) return@firstOrNull false
            val rest = p.stationIds.drop(i + 1)
            when {
                a.nextStationId != null -> rest.firstOrNull()?.let { sameComplex(network, it, a.nextStationId) } == true
                a.destinationStationId != null -> sameComplex(network, p.stationIds[i], a.destinationStationId) || rest.any { sameComplex(network, it, a.destinationStationId) }
                else -> false
            }
        }

    private fun sameComplex(network: TransitNetwork, a: String, b: String): Boolean =
        a == b || network.stationById[a]?.complexId?.let { it == network.stationById[b]?.complexId } == true

    private const val DEFAULT_HOP_SECS = 120
}
