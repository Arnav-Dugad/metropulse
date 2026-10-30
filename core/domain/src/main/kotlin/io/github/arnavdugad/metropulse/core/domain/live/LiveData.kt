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
    /** "Platform 2", when the feed says. */
    val platform: String? = null,
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
            byGroup.getOrPut(group) { ArrayList() } += Departure((a.epochSec - midnight).toInt(), headsign, a.platform)
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

/**
 * Places live trains on their line: each predicted train sits between the stop before its next
 * stop and that stop, by how long the timetable takes for that hop. [prepare] matches a snapshot
 * to the network once; [alongAt] is then cheap enough to call every frame, so trains glide.
 */
class LiveTrains(private val network: TransitNetwork) {

    /** A train heading for [pattern]'s stop at [toM], due at [arrivalEpochSec]. */
    data class Train(
        val key: String,
        val lineId: String,
        val pattern: Pattern,
        val fromM: Double,
        val toM: Double,
        val hopSecs: Int,
        val arrivalEpochSec: Long,
    )

    private val patternsByLine = network.patterns.groupBy { it.lineId }
    private val profileByPattern = network.profiles.groupBy { it.patternId }.mapValues { it.value.first() }

    /** Trains of [lineIds] (all lines when null) that the snapshot identifies by vehicle. */
    fun prepare(live: LiveSnapshot, lineIds: Set<String>? = null): List<Train> {
        val out = ArrayList<Train>()
        val byVehicle = live.arrivals.filter { it.vehicle != null && (lineIds == null || it.lineId in lineIds) }.groupBy { it.lineId to it.vehicle!! }
        for ((key, arrivals) in byVehicle) {
            // Its next stop: the earliest prediction that is not already in the past.
            val next = arrivals.filter { it.epochSec >= live.fetchedAtEpochSec - 30 }.minByOrNull { it.epochSec } ?: continue
            val pattern = patternFor(next) ?: continue
            val i = pattern.stationIds.indexOfFirst { sameComplex(it, next.stationId) }
            if (i <= 0) continue
            val profile = profileByPattern[pattern.id]
            val hop = profile?.let { (it.arrivals[i] - it.departures[i - 1]).coerceAtLeast(30) } ?: DEFAULT_HOP_SECS
            out += Train("${key.first}/${key.second}", key.first, pattern, pattern.stopDistM[i - 1], pattern.stopDistM[i], hop, next.epochSec)
        }
        return out
    }

    /** Metres along the train's pattern at [nowEpochSec] (fractional seconds give smooth motion). */
    fun alongAt(t: Train, nowEpochSec: Double): Double {
        val eta = (t.arrivalEpochSec - nowEpochSec).coerceAtLeast(0.0)
        val fraction = (1.0 - eta / t.hopSecs).coerceIn(0.0, 1.0)
        return t.fromM + (t.toM - t.fromM) * fraction
    }

    /** A pattern of the train's line that reaches its next stop, heading where the feed says it goes. */
    private fun patternFor(a: LiveArrival): Pattern? =
        patternsByLine[a.lineId].orEmpty().firstOrNull { p ->
            val i = p.stationIds.indexOfFirst { sameComplex(it, a.stationId) }
            if (i < 0) return@firstOrNull false
            val rest = p.stationIds.drop(i + 1)
            when {
                a.nextStationId != null -> rest.firstOrNull()?.let { sameComplex(it, a.nextStationId) } == true
                a.destinationStationId != null -> sameComplex(p.stationIds[i], a.destinationStationId) || rest.any { sameComplex(it, a.destinationStationId) }
                else -> false
            }
        }

    private fun sameComplex(a: String, b: String): Boolean =
        a == b || network.stationById[a]?.complexId?.let { it == network.stationById[b]?.complexId } == true

    private companion object {
        const val DEFAULT_HOP_SECS = 120
    }
}

object LivePositions {

    /** Where each live train is at [nowEpochSec], as positions the map and strip maps draw. */
    fun estimate(network: TransitNetwork, live: LiveSnapshot, nowEpochSec: Long, lineIds: Set<String>): List<TrainPosition> {
        val placer = LiveTrains(network)
        return placer.prepare(live, lineIds).map { t ->
            val along = placer.alongAt(t, nowEpochSec.toDouble())
            TrainPosition(t.key.hashCode(), t.pattern.id, t.lineId, along, atStation = along >= t.toM)
        }
    }
}

/** How bad a disruption is, worst first. */
enum class AlertSeverity { Closure, Severe, Minor, Info }

/** A disruption an operator reports for some lines and/or stations. */
data class ServiceAlert(
    val id: String,
    val severity: AlertSeverity,
    val title: String,
    val body: String,
    val lineIds: Set<String>,
    val stationIds: Set<String>,
    /** Epoch seconds; null when open-ended. */
    val activeFrom: Long? = null,
    val activeTo: Long? = null,
) {
    fun activeAt(epochSec: Long): Boolean = (activeFrom == null || epochSec >= activeFrom) && (activeTo == null || epochSec < activeTo)
}

/** Alerts affecting a line, or a station complex (directly, or through the lines serving it). */
object Alerts {
    fun forLine(alerts: List<ServiceAlert>, lineId: String): List<ServiceAlert> =
        alerts.filter { lineId in it.lineIds && it.stationIds.isEmpty() }.sortedBy { it.severity }

    fun forStation(network: TransitNetwork, alerts: List<ServiceAlert>, complexId: String): List<ServiceAlert> {
        val members = network.complexes[complexId].orEmpty().mapTo(HashSet()) { it.id }
        val lines = network.linesAtComplex[complexId].orEmpty().mapTo(HashSet()) { it.id }
        return alerts.filter { a ->
            a.stationIds.any { it in members } || (a.stationIds.isEmpty() && a.lineIds.any { it in lines })
        }.sortedBy { it.severity }
    }

    /** Alerts touching any line a journey rides or any station it boards, changes or alights at. */
    fun forJourney(network: TransitNetwork, alerts: List<ServiceAlert>, journey: io.github.arnavdugad.metropulse.core.domain.routing.Journey): List<ServiceAlert> {
        val lines = journey.rides.mapTo(HashSet()) { it.lineId }
        val stops = journey.rides.flatMapTo(HashSet()) { listOf(it.fromStationId, it.toStationId) }
        return alerts.filter { a -> a.lineIds.any { it in lines } || a.stationIds.any { it in stops } }.distinctBy { it.id }.sortedBy { it.severity }
    }
}

/** A planned ride checked against live predictions. Times are seconds after the service day's midnight. */
data class LiveRide(
    /** The first live departure the rider can catch; null if none is predicted. */
    val departSecs: Int?,
    /** That train's predicted arrival where the rider gets off, when the feed predicts it. */
    val arriveSecs: Int?,
    /** The planned train looks out of reach: the live departure is later than planned. */
    val later: Boolean,
)

object LiveJourney {

    /**
     * For each ride of [journey], the first live train the rider can catch: the first ride from
     * now, each later one after the previous train's predicted arrival plus [changeSecs].
     */
    fun check(network: TransitNetwork, journey: io.github.arnavdugad.metropulse.core.domain.routing.Journey, live: LiveSnapshot, date: LocalDate, nowSecs: Int, changeSecs: Int = 120): List<LiveRide?> {
        val midnight = date.atStartOfDay(network.timezone).toEpochSecond()
        var readyAt = nowSecs
        return journey.rides.map { ride ->
            val pattern = network.patternById[ride.patternId] ?: return@map null
            val next = pattern.stationIds.getOrNull(ride.boardIndex + 1) ?: return@map null
            val board = live.arrivals.filter { a ->
                a.lineId == ride.lineId && sameComplex(network, a.stationId, ride.fromStationId) &&
                    (a.nextStationId?.let { sameComplex(network, it, next) }
                        ?: a.destinationStationId?.let { d -> pattern.stationIds.drop(ride.boardIndex + 1).any { sameComplex(network, it, d) } } ?: false)
            }.map { it to (it.epochSec - midnight).toInt() }.filter { it.second >= readyAt }.minByOrNull { it.second }
                ?: return@map null.also { readyAt = (ride.arriveSecs ?: readyAt) + changeSecs }
            val (arrival, departSecs) = board
            val arriveSecs = arrival.vehicle?.let { v ->
                live.arrivals.firstOrNull { it.vehicle == v && it.lineId == ride.lineId && sameComplex(network, it.stationId, ride.toStationId) }
            }?.let { (it.epochSec - midnight).toInt() }
            readyAt = (arriveSecs ?: ride.arriveSecs ?: departSecs) + changeSecs
            LiveRide(departSecs, arriveSecs, later = ride.departSecs?.let { departSecs > it + 60 } == true)
        }
    }

    private fun sameComplex(network: TransitNetwork, a: String, b: String): Boolean =
        a == b || network.stationById[a]?.complexId?.let { it == network.stationById[b]?.complexId } == true
}
