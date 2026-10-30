package io.github.arnavdugad.metropulse.core.data.city

import io.github.arnavdugad.metropulse.core.data.city.db.CityDao
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.fare.FareData
import io.github.arnavdugad.metropulse.core.domain.fare.FareDay
import io.github.arnavdugad.metropulse.core.domain.fare.FareDiscount
import io.github.arnavdugad.metropulse.core.domain.fare.FareRule
import io.github.arnavdugad.metropulse.core.domain.fare.PeakWindow
import io.github.arnavdugad.metropulse.core.domain.fare.ZonalFares
import io.github.arnavdugad.metropulse.core.domain.fare.ZoneFare
import io.github.arnavdugad.metropulse.core.domain.fare.ZoneRule
import io.github.arnavdugad.metropulse.core.domain.fare.SlabUnit
import io.github.arnavdugad.metropulse.core.domain.fare.Timeframe
import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import io.github.arnavdugad.metropulse.core.domain.geo.Polyline
import io.github.arnavdugad.metropulse.core.domain.transit.DataSource
import io.github.arnavdugad.metropulse.core.domain.transit.DayType
import io.github.arnavdugad.metropulse.core.domain.transit.Line
import io.github.arnavdugad.metropulse.core.domain.transit.LineHours
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.Profile
import io.github.arnavdugad.metropulse.core.domain.transit.Service
import io.github.arnavdugad.metropulse.core.domain.transit.Station
import io.github.arnavdugad.metropulse.core.domain.transit.StationExit
import io.github.arnavdugad.metropulse.core.domain.transit.Track
import io.github.arnavdugad.metropulse.core.domain.transit.TrackKind
import io.github.arnavdugad.metropulse.core.domain.transit.Transfer
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.transit.Trip
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId

/** Reads a whole city database into a [TransitNetwork] (a few hundred KB; done once per city). */
object CityNetworkLoader {

    suspend fun load(dao: CityDao): TransitNetwork {
        val meta = dao.meta().associate { it.key to it.value }
        val stopsByPattern = dao.patternStops().groupBy { it.patternId }
        val patterns = dao.patterns().map { p ->
            val stops = stopsByPattern[p.id].orEmpty()
            Pattern(
                id = p.id, lineId = p.lineId, direction = p.direction, headsign = p.headsign,
                stationIds = stops.map { it.stationId },
                stopDistM = DoubleArray(stops.size) { stops[it].distM },
                shape = LineString(Polyline.decode(p.shape)),
            )
        }
        val exceptions = dao.serviceExceptions().groupBy { it.serviceId }
        val odTable = HashMap<String, HashMap<String, Int>>()
        for (f in dao.fareOd()) odTable.getOrPut(f.fromStation) { HashMap() }[f.toStation] = f.farePaise

        return TransitNetwork(
            cityId = meta["city_id"].orEmpty(),
            cityName = meta["city_name"].orEmpty(),
            timezone = runCatching { ZoneId.of(meta["timezone"]) }.getOrDefault(ZoneId.of("Asia/Kolkata")),
            dataVersion = meta["data_version"]?.toLongOrNull() ?: 0,
            currency = meta["currency"] ?: "INR",
            stationCodes = parseCodes(meta["station_codes"]),
            routeCodes = parseCodes(meta["route_codes"]),
            stepFree = parseAccess(meta["station_access"]),
            facilities = parseFacilities(meta["station_facilities"]),
            lines = dao.lines().map {
                Line(it.id, it.name, it.longName, it.color, it.textColor, it.mode, it.operator, DataQuality.parse(it.quality), it.sortOrder)
            },
            stations = dao.stations().map { Station(it.id, it.name, LatLon(it.lat, it.lon), it.complexId) },
            patterns = patterns,
            profiles = dao.profiles().map { Profile(it.id, it.patternId, it.arrivals.toIntArray(), it.departures.toIntArray()) },
            trips = dao.trips().map { Trip(it.id, it.patternId, it.profileId, it.serviceId, it.startSecs) },
            services = dao.services().map { s ->
                Service(s.id, s.days, s.startDate, s.endDate, exceptions[s.id].orEmpty().associate { it.date to (it.added == 1) })
            },
            transfers = dao.transfers().map { Transfer(it.fromStation, it.toStation, it.walkSecs) },
            lineHours = dao.lineHours().mapNotNull { h ->
                DayType.parse(h.dayType)?.let { LineHours(h.lineId, it, h.firstSecs, h.lastSecs) }
            },
            unknownTimetables = parseUnknown(meta["unknown_timetables"]),
            exits = dao.exits().map {
                StationExit(it.stationId, it.name, LatLon(it.lat, it.lon), it.wheelchair?.let { w -> w == 1 })
            },
            tracks = dao.tracks().map {
                Track(TrackKind.parse(it.kind), it.lineIds.split(',').filter(String::isNotEmpty), Polyline.decode(it.geometry))
            },
            fares = FareData(
                od = odTable,
                rules = dao.fareRules().map {
                    FareRule(if (it.kind == "stations") SlabUnit.Stations else SlabUnit.Km, it.upper, it.farePaise, FareDay.parse(it.dayType), it.lineId)
                },
                discounts = dao.fareDiscounts().map {
                    FareDiscount(it.media, Timeframe.parse(it.timeframe), FareDay.parse(it.dayType), it.lineId, it.percent, it.note)
                },
                peakWindows = dao.farePeakWindows().map { PeakWindow(it.days, it.startSecs, it.endSecs) },
                zonal = parseZonal(meta["fare_zones"]),
            ),
            sources = dao.sources().map { DataSource(it.what, it.url, it.licence, it.attribution) },
        )
    }

    private fun String.toIntArray(): IntArray =
        if (isEmpty()) IntArray(0) else split(',').map { it.trim().toInt() }.toIntArray()

    /** meta.fare_zones: {"rule": "span", "zones": {station: [1, 2]}, "fares": [{lo, hi, peak, offpeak}], "peak": [{days, from, to}]} */
    internal fun parseZonal(text: String?): ZonalFares? = runCatching {
        if (text.isNullOrBlank()) return null
        val o = Json.parseToJsonElement(text).jsonObject
        ZonalFares(
            rule = if (o["rule"]?.jsonPrimitive?.content == "count") ZoneRule.Count else ZoneRule.Span,
            stationZones = o["zones"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.int } },
            fares = o["fares"]!!.jsonArray.map { f ->
                val r = f.jsonObject
                ZoneFare(r["lo"]!!.jsonPrimitive.int to r["hi"]!!.jsonPrimitive.int, r["peak"]!!.jsonPrimitive.int, r["offpeak"]!!.jsonPrimitive.int)
            },
            peak = o["peak"]?.jsonArray.orEmpty().map { w ->
                val r = w.jsonObject
                PeakWindow(r["days"]!!.jsonPrimitive.int, r["from"]!!.jsonPrimitive.int, r["to"]!!.jsonPrimitive.int)
            },
        )
    }.getOrNull()

    /** meta.station_codes / route_codes: {"external id": "our id", ...} */
    internal fun parseCodes(text: String?): Map<String, String> = runCatching {
        Json.parseToJsonElement(text ?: return emptyMap()).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }.getOrDefault(emptyMap())

    /** meta.station_access: {"station id": true, ...} from OpenStreetMap wheelchair tags. */
    internal fun parseAccess(text: String?): Map<String, Boolean> = runCatching {
        Json.parseToJsonElement(text ?: return emptyMap()).jsonObject.mapValues { it.value.jsonPrimitive.content.toBoolean() }
    }.getOrDefault(emptyMap())

    /** meta.station_facilities: {"station id": ["atm", "lift", ...], ...} */
    internal fun parseFacilities(text: String?): Map<String, List<String>> = runCatching {
        Json.parseToJsonElement(text ?: return emptyMap()).jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
    }.getOrDefault(emptyMap())

    /** meta.unknown_timetables: {"line-id": ["weekday", "saturday"], ...} */
    internal fun parseUnknown(text: String?): Map<String, Set<DayType>> {
        if (text.isNullOrBlank()) return emptyMap()
        return Json.parseToJsonElement(text).jsonObject.mapValues { (_, days) ->
            days.jsonArray.mapNotNull { DayType.parse(it.jsonPrimitive.content) }.toSet()
        }
    }
}
