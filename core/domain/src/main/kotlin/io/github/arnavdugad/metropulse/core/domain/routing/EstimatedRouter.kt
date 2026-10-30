package io.github.arnavdugad.metropulse.core.domain.routing

import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import java.time.LocalDate
import java.util.PriorityQueue

/** Average speed between stations, used only where no run times are published or modelled. */
internal const val ESTIMATE_SPEED_MPS = 33 / 3.6
internal const val ESTIMATE_DWELL_SECS = 30

/**
 * Finds routes on lines without a timetable (Delhi publishes only first and last trains). It
 * returns the stations and changes with an *estimated* ride time and no clock times; the app
 * labels these "times unavailable". Shortest path over (station, train) states with a penalty per
 * boarding, so a lower penalty favours speed and a higher one fewer changes.
 */
internal class EstimatedRouter(private val network: TransitNetwork) {
    private val stopIds = network.stations.map { it.id }
    private val stopIndex = stopIds.withIndex().associate { (i, id) -> id to i }
    private val patterns = network.patterns
    private val rideBase = IntArray(patterns.size).also { base ->
        var next = stopIds.size
        patterns.forEachIndexed { i, p -> base[i] = next; next += p.size }
    }
    private val nodeCount = stopIds.size + patterns.sumOf { it.size }

    /** Seconds from stop i to stop i + 1 (including the dwell at i) per pattern. */
    private val hop: List<IntArray> = patterns.map { p -> hopTimes(p) }
    private val ridesAtStop: Array<IntArray> = run {
        val at = Array(stopIds.size) { ArrayList<Int>() }
        patterns.forEachIndexed { pi, p -> p.stationIds.forEachIndexed { i, s -> at[stopIndex.getValue(s)] += rideBase[pi] + i } }
        Array(stopIds.size) { at[it].toIntArray() }
    }
    private val foot: Array<List<Pair<Int, Int>>>
    private val changeSecs = IntArray(stopIds.size) { DEFAULT_CHANGE_SECS }

    init {
        val f = Array(stopIds.size) { ArrayList<Pair<Int, Int>>() }
        for (t in network.transfers) {
            val a = stopIndex[t.fromStationId] ?: continue
            val b = stopIndex[t.toStationId] ?: continue
            if (a == b) changeSecs[a] = t.walkSecs else f[a] += b to t.walkSecs
        }
        foot = Array(stopIds.size) { f[it] }
    }

    private fun hopTimes(p: Pattern): IntArray {
        val profile = network.profiles.firstOrNull { it.patternId == p.id }
        return IntArray((p.size - 1).coerceAtLeast(0)) { i ->
            if (profile != null) {
                profile.arrivals[i + 1] - profile.departures[i] + (profile.departures[i] - profile.arrivals[i])
            } else {
                ((p.stopDistM[i + 1] - p.stopDistM[i]) / ESTIMATE_SPEED_MPS).toInt() + if (i > 0) ESTIMATE_DWELL_SECS else 0
            }
        }
    }

    fun route(origins: Collection<String>, targets: Collection<String>, date: LocalDate, boardPenaltySecs: Int): Journey? {
        val originIdx = origins.mapNotNull(stopIndex::get)
        val targetIdx = targets.mapNotNull(stopIndex::get).toSet()
        if (originIdx.isEmpty() || targetIdx.isEmpty() || originIdx.any { it in targetIdx }) return null

        val cost = IntArray(nodeCount) { INF }
        val prev = IntArray(nodeCount) { -1 }
        val queue = PriorityQueue<Long>()
        fun push(node: Int, c: Int, from: Int) {
            if (c < cost[node]) {
                cost[node] = c; prev[node] = from
                queue += (c.toLong() shl 32) or node.toLong()
            }
        }
        for (s in originIdx) push(s, 0, -1)
        var reached = -1
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            val node = (e and 0xffffffffL).toInt()
            val c = (e ushr 32).toInt()
            if (c > cost[node]) continue
            if (node < stopIds.size) {
                if (node in targetIdx) { reached = node; break }
                for (ride in ridesAtStop[node]) push(ride, c + boardPenaltySecs, node)
                for ((to, secs) in foot[node]) push(to, c + secs, node)
            } else {
                val pi = patternOf(node)
                val pos = node - rideBase[pi]
                val p = patterns[pi]
                if (pos + 1 < p.size) push(node + 1, c + hop[pi][pos], node)
                val stop = stopIndex.getValue(p.stationIds[pos])
                // Alighting: the change time is charged here and refunded at the destination.
                push(stop, c + if (stop in targetIdx) 0 else changeSecs[stop], node)
            }
        }
        if (reached < 0) return null
        return buildJourney(reached, prev, date)
    }

    private fun patternOf(node: Int): Int {
        var lo = 0
        var hi = rideBase.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rideBase[mid] <= node) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun buildJourney(target: Int, prev: IntArray, date: LocalDate): Journey {
        val path = generateSequence(target) { prev[it].takeIf { p -> p >= 0 } }.toList().asReversed()
        val legs = ArrayList<Leg>()
        var i = 0
        while (i < path.size) {
            val node = path[i]
            if (node >= stopIds.size) {
                val pi = patternOf(node)
                val p = patterns[pi]
                var j = i
                while (j + 1 < path.size && path[j + 1] >= stopIds.size && patternOf(path[j + 1]) == pi) j++
                val board = node - rideBase[pi]
                val alight = path[j] - rideBase[pi]
                legs += Leg.Ride(
                    lineId = p.lineId, patternId = p.id, headsign = p.headsign,
                    boardIndex = board, alightIndex = alight,
                    fromStationId = p.stationIds[board], toStationId = p.stationIds[alight],
                    departSecs = null, arriveSecs = null,
                    durationSecs = (board until alight).sumOf { hop[pi][it] },
                    distanceM = p.stopDistM[alight] - p.stopDistM[board], tripId = null,
                )
                i = j + 1
            } else {
                val next = path.getOrNull(i + 1)
                if (next != null && next < stopIds.size) {
                    val secs = foot[node].first { it.first == next }.second
                    legs += Leg.Walk(stopIds[node], stopIds[next], secs)
                }
                i++
            }
        }
        val rides = legs.filterIsInstance<Leg.Ride>()
        val changes = rides.zipWithNext().sumOf { (a, b) ->
            if (a.toStationId == b.fromStationId) changeSecs[stopIndex.getValue(a.toStationId)] else 0
        }
        return Journey(
            serviceDate = date, legs = legs, departSecs = null, arriveSecs = null,
            durationSecs = legs.sumOf { it.durationSecs } + changes,
            quality = DataQuality.Approximate, timed = false,
        )
    }
}
