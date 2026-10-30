package io.github.arnavdugad.metropulse.core.domain.transit

import java.time.LocalDate

/** One train leaving: seconds after the service day's midnight (may exceed 86 400) and where it ends. */
data class Departure(val secs: Int, val headsign: String)

/**
 * Departures of one line in one direction from a station: the next few by the timetable, today's
 * first and last, and the platform (station id) they leave from. [headsign] is the furthest
 * terminus in that direction; trains that turn back earlier carry their own headsign in [next].
 * [timed] is false for lines whose operator publishes no timetable; then only the published
 * operating hours are known.
 */
data class DepartureGroup(
    val line: Line,
    val headsign: String,
    val stationId: String,
    val next: List<Departure>,
    val firstSecs: Int?,
    val lastSecs: Int?,
    val timed: Boolean,
    /** Which way the group runs: the pattern direction and the next stop after [stationId]. */
    val direction: Int = 0,
    val nextStationId: String = "",
)

object Departures {

    /**
     * Departures from the station complex [complexId] on [date] after [nowSecs], in line order.
     * Trains are grouped by line, direction and the next stop, so short trips join the main service
     * while branches stay apart. Includes late trains of the previous service day.
     */
    fun at(network: TransitNetwork, complexId: String, date: LocalDate, nowSecs: Int, count: Int = 3): List<DepartureGroup> {
        val stationIds = network.complexes[complexId].orEmpty().mapTo(HashSet()) { it.id }
        val dayType = network.dayType(date)
        val runningToday = network.services.filter { it.runsOn(date) }.mapTo(HashSet()) { it.id }
        val runningYesterday = network.services.filter { it.runsOn(date.minusDays(1)) }.mapTo(HashSet()) { it.id }
        val tripsByPattern = network.trips.groupBy { it.patternId }

        class Acc(val line: Line, val direction: Int, val stationId: String, val nextStationId: String) {
            var headsign = ""
            var reach = -1
            val today = ArrayList<Int>()
            val upcoming = ArrayList<Departure>()
        }
        val groups = LinkedHashMap<Triple<String, Int, String>, Acc>()
        for (pattern in network.patterns) {
            val line = network.lineById[pattern.lineId] ?: continue
            // Every visit to the station except at the terminus, where trains arrive but do not leave.
            for (pos in 0 until pattern.size - 1) {
                val stationId = pattern.stationIds[pos]
                if (stationId !in stationIds) continue
                val acc = groups.getOrPut(Triple(line.id, pattern.direction, pattern.stationIds[pos + 1])) { Acc(line, pattern.direction, stationId, pattern.stationIds[pos + 1]) }
                val reach = pattern.size - pos
                if (reach > acc.reach) {
                    acc.reach = reach
                    acc.headsign = pattern.headsign
                }
                for (trip in tripsByPattern[pattern.id].orEmpty()) {
                    val profile = network.profileById[trip.profileId] ?: continue
                    val offset = profile.departures[pos]
                    if (trip.serviceId in runningToday) {
                        val t = trip.startSecs + offset
                        acc.today += t
                        if (t >= nowSecs) acc.upcoming += Departure(t, pattern.headsign)
                    }
                    if (trip.serviceId in runningYesterday) {
                        val t = trip.startSecs + offset - 86_400
                        if (t >= nowSecs) acc.upcoming += Departure(t, pattern.headsign)
                    }
                }
            }
        }
        return groups.values
            .map { acc ->
                val timed = network.hasTimetable(acc.line.id, dayType) && acc.today.isNotEmpty()
                val hours = network.hours(acc.line.id, dayType)
                DepartureGroup(
                    line = acc.line,
                    headsign = acc.headsign,
                    stationId = acc.stationId,
                    next = acc.upcoming.sortedBy { it.secs }.distinctBy { it.secs }.take(count),
                    firstSecs = acc.today.minOrNull() ?: hours?.firstSecs,
                    lastSecs = acc.today.maxOrNull() ?: hours?.lastSecs,
                    timed = timed,
                    direction = acc.direction,
                    nextStationId = acc.nextStationId,
                )
            }
            .sortedWith(compareBy({ it.line.sortOrder }, { it.headsign }))
    }
}
