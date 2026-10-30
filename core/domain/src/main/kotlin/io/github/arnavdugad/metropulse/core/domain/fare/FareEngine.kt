package io.github.arnavdugad.metropulse.core.domain.fare

import io.github.arnavdugad.metropulse.core.domain.routing.Journey
import io.github.arnavdugad.metropulse.core.domain.routing.Leg
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

enum class SlabUnit { Km, Stations }

/** Which days a fare row applies to. Holiday rates are applied on Sundays (holidays are not known). */
enum class FareDay(val key: String) {
    All("all"), Weekday("weekday"), Holiday("holiday");

    fun matches(date: LocalDate): Boolean = when (this) {
        All -> true
        Weekday -> date.dayOfWeek != DayOfWeek.SUNDAY
        Holiday -> date.dayOfWeek == DayOfWeek.SUNDAY
    }

    companion object {
        fun parse(key: String): FareDay = entries.firstOrNull { it.key == key } ?: All
    }
}

enum class Timeframe(val key: String) {
    All("all"), Peak("peak"), OffPeak("offpeak");

    companion object {
        fun parse(key: String): Timeframe = entries.firstOrNull { it.key == key } ?: All
    }
}

/** A fare slab: journeys up to [upper] km or stations cost [farePaise]. */
data class FareRule(val unit: SlabUnit, val upper: Double, val farePaise: Int, val day: FareDay, val lineId: String)

data class FareDiscount(
    /** smart_card, qr_ticket or ncmc */
    val media: String,
    val timeframe: Timeframe,
    val day: FareDay,
    /** "" = every line */
    val lineId: String,
    val percent: Double,
    val note: String,
)

/** Peak hours for timeframe-specific discounts. [days]: Mon = 1 ... Sun = 64. */
data class PeakWindow(val days: Int, val startSecs: Int, val endSecs: Int) {
    fun covers(date: LocalDate, secs: Int): Boolean = days and (1 shl (date.dayOfWeek.value - 1)) != 0 && secs in startSecs until endSecs
}

/** How a zonal system prices a journey. */
enum class ZoneRule {
    /** By the lowest and highest zone the journey touches (London). */
    Span,

    /** By how many zones the journey passes through (Dubai). */
    Count,
}

/** One row of a zonal fare table, in minor units. [zones] is (lowest, highest) for Span, (n, n) for Count. */
data class ZoneFare(val zones: Pair<Int, Int>, val peak: Int, val offPeak: Int)

/** A zonal fare system: each station's zone(s) (boundary stations list two) and the fare table. */
class ZonalFares(
    val rule: ZoneRule,
    val stationZones: Map<String, List<Int>>,
    val fares: List<ZoneFare>,
    val peak: List<PeakWindow>,
)

class FareData(
    /** Official origin-destination table, from station id -> to station id -> paise. */
    val od: Map<String, Map<String, Int>>,
    val rules: List<FareRule>,
    val discounts: List<FareDiscount>,
    val peakWindows: List<PeakWindow>,
    val zonal: ZonalFares? = null,
) {
    val isEmpty: Boolean get() = od.isEmpty() && rules.isEmpty() && zonal == null

    companion object {
        val None = FareData(emptyMap(), emptyList(), emptyList(), emptyList())
    }
}

enum class FareBasis {
    /** The operator's own station-to-station fare table. */
    Table,

    /** A distance slab, measured along the track (may differ from the operator's own figure near a boundary). */
    Distance,

    /** A slab by number of stations travelled. */
    Stations,

    /** By the zones the journey touches. */
    Zones,
}

/** One ticket. Separate operators (Mumbai) need separate tickets. */
data class FareComponent(
    val lineIds: List<String>,
    val fromStationId: String,
    val toStationId: String,
    /** null when the operator's fares are not known. */
    val paise: Int?,
    val basis: FareBasis?,
    /** Distance in km or number of stations used for a slab. */
    val measure: Double?,
)

data class MediaFare(val media: String, val paise: Int, val percent: Double, val notes: List<String>)

data class FareQuote(
    val components: List<FareComponent>,
    /** Token / single-journey price; null if any part is unknown. */
    val totalPaise: Int?,
    /** Prices when paying with a card or app ticket that carries a discount. */
    val media: List<MediaFare>,
) {
    val complete: Boolean get() = totalPaise != null
}

/**
 * Prices a journey from the city's published fares: an origin-destination table where one exists
 * (Bengaluru), otherwise distance or station-count slabs, network-wide or per operator.
 */
class FareEngine(private val network: TransitNetwork) {
    private val fares = network.fares

    /** Lines whose slabs are identical belong to one fare system (one ticket covers them). */
    private val systemOf: Map<String, String> = run {
        val byLine = fares.rules.filter { it.lineId.isNotEmpty() }.groupBy { it.lineId }
        byLine.mapValues { (_, rules) -> rules.sortedWith(compareBy({ it.day }, { it.upper })).joinToString { "${it.unit}${it.upper}${it.farePaise}${it.day}" } }
    }

    /**
     * @param departSecs departure time (seconds after midnight) for peak/off-peak discounts; null
     * when unknown, in which case only discounts that do not depend on the time are offered.
     */
    fun quote(journey: Journey, date: LocalDate, departSecs: Int?): FareQuote? {
        val rides = journey.rides
        if (rides.isEmpty() || fares.isEmpty) return null
        val components = when {
            fares.zonal != null -> listOf(zonalFare(rides, fares.zonal, date, departSecs))
            fares.od.isNotEmpty() -> listOf(tableFare(rides))
            else -> slabFares(rides, date)
        }
        val total = components.takeIf { c -> c.all { it.paise != null } }?.sumOf { it.paise!! }
        val media = if (total == null) emptyList() else mediaFares(components, date, departSecs)
        return FareQuote(components, total, media)
    }

    private fun tableFare(rides: List<Leg.Ride>): FareComponent {
        val from = rides.first().fromStationId
        val to = rides.last().toStationId
        val fromIds = sameComplex(from)
        val toIds = sameComplex(to)
        val paise = fromIds.firstNotNullOfOrNull { a -> toIds.firstNotNullOfOrNull { b -> fares.od[a]?.get(b) } }
        return FareComponent(rides.map { it.lineId }.distinct(), from, to, paise, FareBasis.Table.takeIf { paise != null }, null)
    }

    /**
     * Zonal fare over every station the rides call at. A boundary station counts as whichever of
     * its zones makes the fare cheaper. Peak prices apply in the peak windows, and when the time is
     * not known.
     */
    private fun zonalFare(rides: List<Leg.Ride>, zonal: ZonalFares, date: LocalDate, departSecs: Int?): FareComponent {
        val lineIds = rides.map { it.lineId }.distinct()
        val from = rides.first().fromStationId
        val to = rides.last().toStationId
        val stations = rides.flatMap { r ->
            network.patternById[r.patternId]?.stationIds?.subList(r.boardIndex, r.alightIndex + 1) ?: listOf(r.fromStationId, r.toStationId)
        }
        val zones = stations.mapNotNull { zonal.stationZones[it]?.takeIf(List<Int>::isNotEmpty) }
        if (zones.size < 2) return FareComponent(lineIds, from, to, null, null, null)
        val key = when (zonal.rule) {
            // Inner zones cost more: a boundary station counts in its outer zone for the low end, inner for the high end.
            ZoneRule.Span -> {
                val lo = zones.minOf { it.max() }
                val hi = zones.maxOf { it.min() }
                minOf(lo, hi) to maxOf(lo, hi)
            }
            // Count each zone once; a boundary station adds nothing if one of its zones is already counted.
            ZoneRule.Count -> {
                val counted = HashSet<Int>()
                zones.filter { it.size == 1 }.forEach { counted += it[0] }
                zones.filter { it.size > 1 }.forEach { z -> if (z.none(counted::contains)) counted += z.first() }
                counted.size to counted.size
            }
        }
        val row = zonal.fares.firstOrNull { it.zones == key }
            ?: zonal.fares.filter { zonal.rule == ZoneRule.Count && it.zones.first <= key.first }.maxByOrNull { it.zones.first }
            ?: return FareComponent(lineIds, from, to, null, null, null)
        val peak = departSecs == null || zonal.peak.any { it.covers(date, departSecs) }
        return FareComponent(lineIds, from, to, if (peak) row.peak else row.offPeak, FareBasis.Zones, (key.second - key.first + 1).toDouble())
    }

    private fun sameComplex(stationId: String): List<String> {
        val complex = network.stationById[stationId]?.complexId ?: return listOf(stationId)
        return listOf(stationId) + network.complexes[complex].orEmpty().map { it.id }.filter { it != stationId }
    }

    private fun slabFares(rides: List<Leg.Ride>, date: LocalDate): List<FareComponent> {
        val hasNetwork = fares.rules.any { it.lineId.isEmpty() }
        fun system(lineId: String): String? = systemOf[lineId] ?: if (hasNetwork) "" else null
        val groups = ArrayList<MutableList<Leg.Ride>>()
        var current: String? = null
        for (ride in rides) {
            val s = system(ride.lineId) ?: "?${ride.lineId}"
            if (groups.isEmpty() || s != current) groups += mutableListOf(ride) else groups.last() += ride
            current = s
        }
        return groups.map { group -> slabFare(group, system(group.first().lineId), date) }
    }

    private fun slabFare(rides: List<Leg.Ride>, system: String?, date: LocalDate): FareComponent {
        val lineIds = rides.map { it.lineId }.distinct()
        val from = rides.first().fromStationId
        val to = rides.last().toStationId
        if (system == null) return FareComponent(lineIds, from, to, null, null, null)
        val scope = if (system.isEmpty()) "" else rides.first().lineId
        val candidates = fares.rules.filter { it.lineId == scope }
        // A day-specific table beats the general one.
        val rules = candidates.filter { it.day != FareDay.All && it.day.matches(date) }.ifEmpty { candidates.filter { it.day == FareDay.All } }
            .sortedBy { it.upper }
        if (rules.isEmpty()) return FareComponent(lineIds, from, to, null, null, null)
        val unit = rules.first().unit
        val measure = when (unit) {
            SlabUnit.Km -> rides.sumOf { it.distanceM } / 1000.0
            SlabUnit.Stations -> rides.sumOf { it.stopCount }.toDouble()
        }
        val rule = rules.firstOrNull { measure <= it.upper + 1e-9 } ?: rules.last()
        val basis = if (unit == SlabUnit.Km) FareBasis.Distance else FareBasis.Stations
        return FareComponent(lineIds, from, to, rule.farePaise, basis, measure)
    }

    private fun mediaFares(components: List<FareComponent>, date: LocalDate, departSecs: Int?): List<MediaFare> {
        val peak = departSecs?.let { isPeak(date, it) }
        return fares.discounts.map { it.media }.distinct().mapNotNull { media ->
            var paise = 0.0
            var discounted = false
            val notes = LinkedHashSet<String>()
            var weighted = 0.0
            for (c in components) {
                val d = bestDiscount(media, c.lineIds, date, peak)
                val base = c.paise!!.toDouble()
                if (d != null) {
                    discounted = true
                    notes += d.note
                    paise += base * (1 - d.percent / 100)
                    weighted += base * d.percent
                } else {
                    paise += base
                }
            }
            if (!discounted) return@mapNotNull null
            val total = components.sumOf { it.paise!! }
            MediaFare(media, paise.roundToInt(), if (total == 0) 0.0 else weighted / total, notes.filter { it.isNotBlank() })
        }
    }

    /** The most specific discount for this medium that applies (line > day > time of day). */
    private fun bestDiscount(media: String, lineIds: List<String>, date: LocalDate, peak: Boolean?): FareDiscount? =
        fares.discounts.filter { d ->
            d.media == media && d.day.matches(date) &&
                (d.lineId.isEmpty() || d.lineId in lineIds) &&
                when (d.timeframe) {
                    Timeframe.All -> true
                    Timeframe.Peak -> peak == true
                    Timeframe.OffPeak -> peak == false
                }
        }.maxByOrNull { d ->
            (if (d.lineId.isNotEmpty()) 4 else 0) + (if (d.day != FareDay.All) 2 else 0) + (if (d.timeframe != Timeframe.All) 1 else 0)
        }

    private fun isPeak(date: LocalDate, secs: Int): Boolean {
        val bit = 1 shl (date.dayOfWeek.value - 1)
        val t = secs % 86_400
        return fares.peakWindows.any { w -> w.days and bit != 0 && t >= w.startSecs && t < w.endSecs }
    }
}
