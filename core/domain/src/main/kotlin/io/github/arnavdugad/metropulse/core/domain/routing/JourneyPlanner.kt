package io.github.arnavdugad.metropulse.core.domain.routing

import io.github.arnavdugad.metropulse.core.domain.transit.DayType
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import java.time.LocalDate

/** Penalties per boarding for untimed routes: one favouring speed, one favouring fewer changes. */
private val ESTIMATE_PENALTIES = listOf(300, 1_800)

data class PlanResult(
    /** Timed journeys first (by arrival), then untimed ones (by estimated duration). */
    val journeys: List<Journey>,
    /** Lines without a published timetable on this day; journeys using them are untimed. */
    val untimedLines: Set<String>,
)

/**
 * Plans journeys between two stations (station complexes, as the user sees them). Uses RAPTOR on
 * the timetable, and adds estimated routes where a line has no published timetable for the day.
 */
class JourneyPlanner(private val network: TransitNetwork) {
    private val timetables = object : LinkedHashMap<LocalDate, RaptorTimetable>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, RaptorTimetable>) = size > 3
    }
    private val estimated by lazy { EstimatedRouter(network) }

    /**
     * @param departSecs seconds after [date]'s midnight in the city's time zone.
     * @param departures how many successive departures to include (each adds its Pareto set).
     */
    fun plan(fromComplexId: String, toComplexId: String, date: LocalDate, departSecs: Int, departures: Int = 3): PlanResult {
        val origins = network.complexes[fromComplexId].orEmpty().map { it.id }
        val targets = network.complexes[toComplexId].orEmpty().map { it.id }
        if (origins.isEmpty() || targets.isEmpty() || fromComplexId == toComplexId) return PlanResult(emptyList(), emptySet())

        val raptor = Raptor(timetable(date))
        val timed = ArrayList<Journey>()
        var after = departSecs
        for (i in 0 until departures) {
            val fresh = raptor.search(origins, targets, after).filter { j -> timed.none { it.legs == j.legs } }
            if (fresh.isEmpty()) break
            timed += fresh
            after = fresh.minOf { it.departSecs!! } + 1
        }
        timed.sortWith(compareBy<Journey>({ it.arriveSecs }, { it.transfers }))

        val dayType = network.dayType(date)
        val untimedLines = network.lines.map { it.id }.filterTo(LinkedHashSet()) { !network.hasTimetable(it, dayType) }
        val untimed = if (untimedLines.isEmpty() && timed.isNotEmpty()) {
            emptyList()
        } else {
            ESTIMATE_PENALTIES.mapNotNull { estimated.route(origins, targets, date, it) }
                .distinctBy { it.lineSequence to it.rides.map { r -> r.fromStationId } }
                // Keep estimates that a timed result cannot stand in for.
                .filter { e -> timed.isEmpty() || e.lineSequence.any { it in untimedLines } }
                .filter { e -> timed.none { it.lineSequence == e.lineSequence } }
                .sortedBy { it.durationSecs }
        }
        return PlanResult(timed + untimed, untimedLines)
    }

    private fun timetable(date: LocalDate): RaptorTimetable = synchronized(timetables) {
        timetables.getOrPut(date) { RaptorTimetable(network, date) }
    }
}
