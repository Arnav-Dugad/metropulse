package io.github.arnavdugad.metropulse.core.domain.transit

import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.fare.FareData
import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

// Times are seconds after the service day's midnight (local time) and may exceed 86 400 for
// trains that run after midnight. Dates are yyyymmdd integers, as in the city database.

data class Line(
    val id: String,
    val name: String,
    val longName: String,
    /** 0xRRGGBB */
    val color: Int,
    val textColor: Int,
    val mode: String,
    val operator: String,
    val quality: DataQuality,
    val sortOrder: Int,
)

enum class DayType(val key: String) {
    Weekday("weekday"), Friday("friday"), Saturday("saturday"), Sunday("sunday");

    companion object {
        /** Use [TransitNetwork.dayType]: Friday counts as a weekday where the city has no Friday timetable. */
        fun of(date: LocalDate): DayType = when (date.dayOfWeek) {
            DayOfWeek.FRIDAY -> Friday
            DayOfWeek.SATURDAY -> Saturday
            DayOfWeek.SUNDAY -> Sunday
            else -> Weekday
        }

        fun parse(key: String): DayType? = entries.firstOrNull { it.key == key }
    }
}

/** Published first and last departures of a line on a day type. */
data class LineHours(val lineId: String, val dayType: DayType, val firstSecs: Int, val lastSecs: Int)

data class Station(
    val id: String,
    val name: String,
    val location: LatLon,
    /** Platforms of one interchange share a complex id; the app shows them as one station. */
    val complexId: String,
)

data class StationExit(val stationId: String, val name: String, val location: LatLon, val wheelchair: Boolean?)

/** One stopping sequence of a line in one direction. */
class Pattern(
    val id: Int,
    val lineId: String,
    val direction: Int,
    val headsign: String,
    val stationIds: List<String>,
    /** Distance of each stop along [shape], in metres. */
    val stopDistM: DoubleArray,
    val shape: LineString,
) {
    val size: Int get() = stationIds.size
}

/** Run times shared by trips: offsets in seconds from the trip's first departure. */
class Profile(val id: Int, val patternId: Int, val arrivals: IntArray, val departures: IntArray)

data class Trip(val id: Int, val patternId: Int, val profileId: Int, val serviceId: String, val startSecs: Int)

/** Which dates a set of trips runs on. */
data class Service(
    val id: String,
    /** Mon = 1, Tue = 2 ... Sun = 64 */
    val days: Int,
    val startDate: Int,
    val endDate: Int,
    /** yyyymmdd -> true (added) / false (removed) */
    val exceptions: Map<Int, Boolean> = emptyMap(),
) {
    fun runsOn(date: LocalDate): Boolean {
        val key = date.toKey()
        exceptions[key]?.let { return it }
        return key in startDate..endDate && days and (1 shl (date.dayOfWeek.value - 1)) != 0
    }
}

fun LocalDate.toKey(): Int = year * 10000 + monthValue * 100 + dayOfMonth

/** A walking link. from == to is the minimum time to change platforms within a station. */
data class Transfer(val fromStationId: String, val toStationId: String, val walkSecs: Int)

data class Track(val kind: TrackKind, val lineIds: List<String>, val geometry: List<LatLon>)

enum class TrackKind {
    Elevated, Underground, AtGrade;

    companion object {
        fun parse(value: String): TrackKind = when (value) {
            "elevated" -> Elevated
            "underground" -> Underground
            else -> AtGrade
        }
    }
}

data class DataSource(val what: String, val url: String, val licence: String, val attribution: String)

/** Everything the app knows about one city's metro, loaded once into memory. */
class TransitNetwork(
    val cityId: String,
    val cityName: String,
    val timezone: ZoneId,
    val dataVersion: Long,
    val lines: List<Line>,
    val stations: List<Station>,
    val patterns: List<Pattern>,
    val profiles: List<Profile>,
    val trips: List<Trip>,
    val services: List<Service>,
    val transfers: List<Transfer>,
    val lineHours: List<LineHours>,
    /** Lines and day types whose timetable the operator has not published. */
    val unknownTimetables: Map<String, Set<DayType>>,
    val exits: List<StationExit>,
    val tracks: List<Track>,
    val fares: FareData,
    val sources: List<DataSource>,
    /** ISO 4217 code of the fares, which are kept in minor units (paise, pence, cents, fils…). */
    val currency: String = "INR",
    /** Live feeds' station ids (TfL StopPoint ids) -> station id. */
    val stationCodes: Map<String, String> = emptyMap(),
    /** GTFS route_id -> line id, for matching realtime feeds. */
    val routeCodes: Map<String, String> = emptyMap(),
    /** Station id -> step-free (true) or not (false), where OpenStreetMap or the operator says. */
    val stepFree: Map<String, Boolean> = emptyMap(),
    /** Station id -> facilities found in or by it ("toilets", "lift", "parking", "atm", "bicycle_parking"). */
    val facilities: Map<String, List<String>> = emptyMap(),
) {
    val lineById: Map<String, Line> = lines.associateBy { it.id }
    val stationById: Map<String, Station> = stations.associateBy { it.id }
    val patternById: Map<Int, Pattern> = patterns.associateBy { it.id }
    val profileById: Map<Int, Profile> = profiles.associateBy { it.id }
    val serviceById: Map<String, Service> = services.associateBy { it.id }

    /** Station complexes (what the user sees as one station), keyed by complex id. */
    val complexes: Map<String, List<Station>> = stations.groupBy { it.complexId }

    /** Lines serving each complex, in line order. */
    val linesAtComplex: Map<String, List<Line>> = run {
        val byStation = HashMap<String, MutableSet<String>>()
        for (p in patterns) for (s in p.stationIds) byStation.getOrPut(s) { LinkedHashSet() } += p.lineId
        complexes.mapValues { (_, members) ->
            members.flatMap { byStation[it.id].orEmpty() }.distinct().mapNotNull(lineById::get).sortedBy { it.sortOrder }
        }
    }

    /** Data built before Friday became a day type (or with no Friday entries) treats Friday as a weekday. */
    private val fridayAware = lineHours.any { it.dayType == DayType.Friday } || unknownTimetables.values.any { DayType.Friday in it }

    fun dayType(date: LocalDate): DayType = DayType.of(date).let { if (it == DayType.Friday && !fridayAware) DayType.Weekday else it }

    fun hasTimetable(lineId: String, dayType: DayType): Boolean = dayType !in unknownTimetables[lineId].orEmpty()

    fun hours(lineId: String, dayType: DayType): LineHours? = lineHours.firstOrNull { it.lineId == lineId && it.dayType == dayType }

    /**
     * First and last departures of a line on [date]: the operator's published hours, or else the
     * first and last trips in the timetable. Null when neither is known.
     */
    fun operatingHours(lineId: String, date: LocalDate): LineHours? {
        val dayType = dayType(date)
        hours(lineId, dayType)?.let { return it }
        val patternIds = patterns.filter { it.lineId == lineId }.mapTo(HashSet()) { it.id }
        val starts = trips.filter { it.patternId in patternIds && serviceById[it.serviceId]?.runsOn(date) == true }.map { it.startSecs }
        if (starts.isEmpty()) return null
        return LineHours(lineId, dayType, starts.min(), starts.max())
    }

    /**
     * Whether a station complex has step-free access: true if any of its platforms or entrances is
     * marked step-free, false if every platform is marked not, null when nobody has said.
     */
    fun stepFree(complexId: String): Boolean? {
        val members = complexes[complexId].orEmpty()
        val known = members.mapNotNull { stepFree[it.id] }
        return when {
            known.any { it } -> true
            exits.any { e -> e.wheelchair == true && members.any { it.id == e.stationId } } -> true
            known.isNotEmpty() && known.size == members.size -> false
            else -> null
        }
    }

    /** Facilities at a station complex, merged over its platforms. */
    fun facilities(complexId: String): List<String> =
        complexes[complexId].orEmpty().flatMap { facilities[it.id].orEmpty() }.distinct()

    /** Display name of a complex. */
    fun complexName(complexId: String): String = complexes[complexId]?.first()?.name ?: complexId
}
