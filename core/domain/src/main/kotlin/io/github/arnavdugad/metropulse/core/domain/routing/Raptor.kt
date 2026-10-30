package io.github.arnavdugad.metropulse.core.domain.routing

import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import java.time.LocalDate

internal const val INF = Int.MAX_VALUE / 2
private const val SECS_PER_DAY = 86_400

/**
 * Time to change platforms at a station without a published value, and the least we plan for even
 * when a feed publishes less: interchanges usually mean stairs between levels, and a missed
 * connection costs the rider far more than a plan that leaves a minute earlier.
 */
internal const val DEFAULT_CHANGE_SECS = 120

/** One pattern's trips on one service day, as flat arrays (trip-major). */
internal class Route(val pattern: Pattern, val stops: IntArray, val tripIds: IntArray, private val dep: IntArray, private val arr: IntArray) {
    val size = stops.size
    val tripCount = tripIds.size

    fun dep(trip: Int, pos: Int) = dep[trip * size + pos]
    fun arr(trip: Int, pos: Int) = arr[trip * size + pos]

    /** First trip departing [pos] at or after [time], or -1. Trips are ordered by departure. */
    fun earliestTrip(pos: Int, time: Int): Int {
        var lo = 0
        var hi = tripCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dep(mid, pos) < time) lo = mid + 1 else hi = mid
        }
        return if (lo < tripCount) lo else -1
    }
}

/** The least reliable of the given qualities (a journey is only as good as its weakest leg). */
internal fun worstQuality(qualities: List<DataQuality?>): DataQuality =
    qualities.maxOfOrNull { (it ?: DataQuality.Approximate).ordinal }?.let { DataQuality.entries[it] } ?: DataQuality.Approximate

/**
 * A service day's timetable laid out for RAPTOR. Times are seconds after [serviceDate]'s
 * midnight; trips from the previous and next service days are included, shifted by a day, so
 * after-midnight trains and tomorrow's first trains are found.
 */
internal class RaptorTimetable(val network: TransitNetwork, val serviceDate: LocalDate) {
    val stopIds: List<String> = network.stations.map { it.id }
    val stopIndex: Map<String, Int> = stopIds.withIndex().associate { (i, id) -> id to i }
    val routes: List<Route>
    val routesAtStop: Array<IntArray>
    val positionsAtStop: Array<IntArray>
    val footTargets: Array<IntArray>
    val footSecs: Array<IntArray>
    val changeSecs: IntArray

    init {
        val days = listOf(serviceDate.minusDays(1) to -SECS_PER_DAY, serviceDate to 0, serviceDate.plusDays(1) to SECS_PER_DAY)
        val running = days.map { (date, offset) -> network.services.filter { it.runsOn(date) }.map { it.id }.toSet() to offset }
        val tripsByPattern = network.trips.groupBy { it.patternId }
        routes = network.patterns.mapNotNull { p ->
            val stops = IntArray(p.size) { stopIndex.getValue(p.stationIds[it]) }
            val runs = ArrayList<Pair<Int, IntArray>>() // trip id, departures then arrivals
            for (trip in tripsByPattern[p.id].orEmpty()) {
                val profile = network.profileById[trip.profileId] ?: continue
                for ((services, offset) in running) {
                    if (trip.serviceId !in services) continue
                    val base = trip.startSecs + offset
                    if (base + profile.arrivals.last() < 0) continue
                    val times = IntArray(p.size * 2)
                    for (i in 0 until p.size) {
                        times[i] = base + profile.departures[i]
                        times[p.size + i] = base + profile.arrivals[i]
                    }
                    runs += trip.id to times
                }
            }
            if (runs.isEmpty()) return@mapNotNull null
            runs.sortBy { it.second[0] }
            val n = p.size
            val dep = IntArray(runs.size * n)
            val arr = IntArray(runs.size * n)
            runs.forEachIndexed { t, (_, times) ->
                System.arraycopy(times, 0, dep, t * n, n)
                System.arraycopy(times, n, arr, t * n, n)
            }
            Route(p, stops, IntArray(runs.size) { runs[it].first }, dep, arr)
        }
        val atStop = Array(stopIds.size) { ArrayList<Pair<Int, Int>>() }
        routes.forEachIndexed { r, route -> route.stops.forEachIndexed { pos, s -> atStop[s] += r to pos } }
        routesAtStop = Array(stopIds.size) { s -> IntArray(atStop[s].size) { atStop[s][it].first } }
        positionsAtStop = Array(stopIds.size) { s -> IntArray(atStop[s].size) { atStop[s][it].second } }

        val foot = Array(stopIds.size) { ArrayList<Pair<Int, Int>>() }
        changeSecs = IntArray(stopIds.size) { DEFAULT_CHANGE_SECS }
        for (t in network.transfers) {
            val a = stopIndex[t.fromStationId] ?: continue
            val b = stopIndex[t.toStationId] ?: continue
            if (a == b) changeSecs[a] = maxOf(t.walkSecs, DEFAULT_CHANGE_SECS) else foot[a] += b to t.walkSecs
        }
        footTargets = Array(stopIds.size) { s -> IntArray(foot[s].size) { foot[s][it].first } }
        footSecs = Array(stopIds.size) { s -> IntArray(foot[s].size) { foot[s][it].second } }
    }
}

/**
 * Round-based public transit routing (Delling, Pajor & Werneck, 2012). Round k finds the earliest
 * arrival using at most k trains, so the rounds together give the Pareto set of
 * (arrival time, number of changes).
 */
internal class Raptor(private val tt: RaptorTimetable, private val maxRides: Int = 6) {

    fun search(origins: Collection<String>, targets: Collection<String>, departSecs: Int): List<Journey> {
        val n = tt.stopIds.size
        val originIdx = origins.mapNotNull(tt.stopIndex::get).toSet()
        val targetIdx = targets.mapNotNull(tt.stopIndex::get).toSet()
        if (originIdx.isEmpty() || targetIdx.isEmpty() || originIdx.any { it in targetIdx }) return emptyList()

        val k1 = maxRides + 1
        val arrByTrip = Array(k1) { IntArray(n) { INF } }
        val ready = Array(k1) { IntArray(n) { INF } }
        val readyRound = Array(k1) { IntArray(n) { -1 } }
        val readyFrom = Array(k1) { IntArray(n) { -1 } } // -1: own arrival or origin; else walked from
        val rideRoute = Array(k1) { IntArray(n) { -1 } }
        val rideTrip = Array(k1) { IntArray(n) }
        val rideBoard = Array(k1) { IntArray(n) }
        val rideAlight = Array(k1) { IntArray(n) }
        val best = IntArray(n) { INF }

        var marked = HashSet<Int>()
        for (s in originIdx) {
            ready[0][s] = departSecs
            readyRound[0][s] = 0
            marked += s
        }
        var targetBest = INF
        val results = ArrayList<Journey>()
        val routeStart = IntArray(tt.routes.size)

        for (k in 1..maxRides) {
            // Stops not improved this round keep their earlier boarding time.
            ready[k - 1].copyInto(ready[k]); readyRound[k - 1].copyInto(readyRound[k]); readyFrom[k - 1].copyInto(readyFrom[k])
            routeStart.fill(Int.MAX_VALUE)
            for (s in marked) {
                val rs = tt.routesAtStop[s]
                val ps = tt.positionsAtStop[s]
                for (j in rs.indices) if (ps[j] < routeStart[rs[j]]) routeStart[rs[j]] = ps[j]
            }
            val arrived = LinkedHashSet<Int>()
            for (r in tt.routes.indices) {
                val start = routeStart[r]
                if (start == Int.MAX_VALUE) continue
                val route = tt.routes[r]
                var trip = -1
                var boardPos = -1
                for (pos in start until route.size) {
                    val s = route.stops[pos]
                    if (trip >= 0) {
                        val a = route.arr(trip, pos)
                        if (a < best[s] && a < targetBest) {
                            arrByTrip[k][s] = a
                            best[s] = a
                            rideRoute[k][s] = r; rideTrip[k][s] = trip; rideBoard[k][s] = boardPos; rideAlight[k][s] = pos
                            arrived += s
                        }
                    }
                    if (pos == route.size - 1) break
                    val r0 = ready[k - 1][s]
                    if (r0 < INF && (trip < 0 || r0 < route.dep(trip, pos))) {
                        val t = route.earliestTrip(pos, r0)
                        if (t >= 0 && (trip < 0 || route.dep(t, pos) < route.dep(trip, pos))) {
                            trip = t
                            boardPos = pos
                        }
                    }
                }
            }
            var bestStop = -1
            for (s in targetIdx) if (arrByTrip[k][s] < targetBest) { targetBest = arrByTrip[k][s]; bestStop = s }
            if (bestStop >= 0) {
                results += reconstruct(k, bestStop, rideRoute, rideTrip, rideBoard, rideAlight, readyRound, readyFrom)
            }

            marked = HashSet()
            for (s in arrived) {
                val a = arrByTrip[k][s]
                val change = a + tt.changeSecs[s]
                if (change < ready[k][s]) {
                    ready[k][s] = change; readyRound[k][s] = k; readyFrom[k][s] = -1; marked += s
                }
                val targets2 = tt.footTargets[s]
                for (j in targets2.indices) {
                    val to = targets2[j]
                    val t = a + tt.footSecs[s][j]
                    if (t < ready[k][to]) {
                        ready[k][to] = t; readyRound[k][to] = k; readyFrom[k][to] = s; marked += to
                    }
                }
            }
            if (marked.isEmpty()) break
        }
        return results
    }

    private class RideRef(val route: Int, var trip: Int, val board: Int, val alight: Int)

    private fun reconstruct(
        k: Int, target: Int,
        rideRoute: Array<IntArray>, rideTrip: Array<IntArray>, rideBoard: Array<IntArray>, rideAlight: Array<IntArray>,
        readyRound: Array<IntArray>, readyFrom: Array<IntArray>,
    ): Journey {
        // Walk back from the target: ride, then how its boarding stop was reached.
        val rides = ArrayList<RideRef>()
        val walksBefore = ArrayList<Pair<Int, Int>?>() // walk (from, to) preceding each ride, if any
        var round = k
        var stop = target
        while (true) {
            val ref = RideRef(rideRoute[round][stop], rideTrip[round][stop], rideBoard[round][stop], rideAlight[round][stop])
            rides += ref
            val boardStop = tt.routes[ref.route].stops[ref.board]
            val origin = readyRound[round - 1][boardStop]
            if (origin == 0) { walksBefore += null; break }
            val from = readyFrom[origin][boardStop]
            if (from < 0) { walksBefore += null; stop = boardStop } else { walksBefore += from to boardStop; stop = from }
            round = origin
        }
        rides.reverse()
        walksBefore.reverse()
        tighten(rides, walksBefore)

        val legs = ArrayList<Leg>()
        rides.forEachIndexed { i, ref ->
            walksBefore[i]?.let { (from, to) ->
                legs += Leg.Walk(tt.stopIds[from], tt.stopIds[to], walkSecs(from, to))
            }
            val route = tt.routes[ref.route]
            val p = route.pattern
            val depart = route.dep(ref.trip, ref.board)
            val arrive = route.arr(ref.trip, ref.alight)
            legs += Leg.Ride(
                lineId = p.lineId, patternId = p.id, headsign = p.headsign,
                boardIndex = ref.board, alightIndex = ref.alight,
                fromStationId = p.stationIds[ref.board], toStationId = p.stationIds[ref.alight],
                departSecs = depart, arriveSecs = arrive, durationSecs = arrive - depart,
                distanceM = p.stopDistM[ref.alight] - p.stopDistM[ref.board], tripId = route.tripIds[ref.trip],
            )
        }
        val first = legs.first { it is Leg.Ride } as Leg.Ride
        val last = legs.last() as Leg.Ride
        val quality = worstQuality(rides.map { tt.network.lineById[tt.routes[it.route].pattern.lineId]?.quality })
        return Journey(
            serviceDate = tt.serviceDate, legs = legs,
            departSecs = first.departSecs, arriveSecs = last.arriveSecs,
            durationSecs = last.arriveSecs!! - first.departSecs!!,
            quality = quality, timed = true,
        )
    }

    /**
     * RAPTOR boards the earliest catchable train, which can leave the rider waiting at a change.
     * Working back from the last ride, take the latest train on each earlier ride that still
     * makes the next connection, so the journey leaves as late as possible.
     */
    private fun tighten(rides: List<RideRef>, walksBefore: List<Pair<Int, Int>?>) {
        for (i in rides.size - 2 downTo 0) {
            val next = rides[i + 1]
            val nextRoute = tt.routes[next.route]
            val ref = rides[i]
            val route = tt.routes[ref.route]
            val alightStop = route.stops[ref.alight]
            val connection = walksBefore[i + 1]?.let { (from, to) -> walkSecs(from, to) } ?: tt.changeSecs[alightStop]
            val deadline = nextRoute.dep(next.trip, next.board) - connection
            var t = ref.trip
            while (t + 1 < route.tripCount && route.arr(t + 1, ref.alight) <= deadline) t++
            ref.trip = t
        }
    }

    private fun walkSecs(from: Int, to: Int): Int {
        val targets = tt.footTargets[from]
        for (j in targets.indices) if (targets[j] == to) return tt.footSecs[from][j]
        return DEFAULT_CHANGE_SECS
    }
}
