package io.github.arnavdugad.metropulse.core.domain.transit

import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.fare.FareData
import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import java.time.LocalDate
import java.time.ZoneId

/** Monday 2 November 2026; the day before is a Sunday. */
val MONDAY: LocalDate = LocalDate.of(2026, 11, 2)
val SUNDAY: LocalDate = LocalDate.of(2026, 11, 1)

fun hm(h: Int, m: Int, s: Int = 0) = h * 3600 + m * 60 + s

/** Builds small synthetic networks for tests. Stations are given on a grid of ~1 km cells. */
class TestNetworkBuilder {
    private val stations = LinkedHashMap<String, Station>()
    private val lines = ArrayList<Line>()
    private val patterns = ArrayList<Pattern>()
    private val profiles = ArrayList<Profile>()
    private val trips = ArrayList<Trip>()
    private val services = LinkedHashMap<String, Service>()
    private val transfers = ArrayList<Transfer>()
    private val unknown = HashMap<String, Set<DayType>>()

    init {
        service("daily", 127)
    }

    fun station(id: String, x: Double, y: Double, complex: String = id, name: String = id) = apply {
        stations[id] = Station(id, name, LatLon(12.9 + y * 0.009, 77.6 + x * 0.0092), complex)
    }

    fun service(id: String, days: Int) = apply { services[id] = Service(id, days, 20260101, 20271231) }

    fun transfer(a: String, b: String, secs: Int) = apply { transfers += Transfer(a, b, secs) }

    private fun line(id: String, quality: DataQuality) {
        if (lines.none { it.id == id }) lines += Line(id, id, "", 0x112233, 0xFFFFFF, "metro", "", quality, lines.size)
    }

    private fun pattern(lineId: String, stationIds: List<String>, direction: Int = 0): Pattern {
        val shape = LineString(stationIds.map { stations.getValue(it).location })
        val dists = DoubleArray(stationIds.size) { shape.project(stations.getValue(stationIds[it]).location).alongM }
        return Pattern(patterns.size + 1, lineId, direction, stations.getValue(stationIds.last()).name, stationIds, dists, shape).also { patterns += it }
    }

    /** A timed route: trains every [headwaySecs] from [firstSecs] to [lastSecs] from the first stop. */
    fun route(
        lineId: String, stationIds: List<String>, firstSecs: Int, lastSecs: Int, headwaySecs: Int,
        hopSecs: Int = 120, dwellSecs: Int = 30, serviceId: String = "daily", quality: DataQuality = DataQuality.Scheduled,
        direction: Int = 0,
    ) = apply {
        line(lineId, quality)
        val p = pattern(lineId, stationIds, direction)
        val arrivals = IntArray(p.size) { i -> if (i == 0) 0 else i * hopSecs + (i - 1) * dwellSecs }
        val departures = IntArray(p.size) { i -> if (i == 0 || i == p.size - 1) arrivals[i] else arrivals[i] + dwellSecs }
        val profile = Profile(profiles.size + 1, p.id, arrivals, departures).also { profiles += it }
        var t = firstSecs
        while (t <= lastSecs) {
            trips += Trip(trips.size + 1, p.id, profile.id, serviceId, t)
            t += headwaySecs
        }
    }

    /** A line without a published timetable (no trips). */
    fun untimedRoute(lineId: String, stationIds: List<String>) = apply {
        line(lineId, DataQuality.Approximate)
        pattern(lineId, stationIds)
        unknown[lineId] = DayType.entries.toSet()
    }

    fun build(fares: FareData = FareData.None) = TransitNetwork(
        cityId = "test", cityName = "Test", timezone = ZoneId.of("Asia/Kolkata"), dataVersion = 1,
        lines = lines, stations = stations.values.toList(), patterns = patterns, profiles = profiles, trips = trips,
        services = services.values.toList(), transfers = transfers, lineHours = emptyList(), unknownTimetables = unknown,
        exits = emptyList(), tracks = emptyList(), fares = fares, sources = emptyList(),
    )
}
