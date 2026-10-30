package io.github.arnavdugad.metropulse.core.data.live

import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.domain.live.AlertSeverity
import io.github.arnavdugad.metropulse.core.domain.live.GtfsRealtime
import io.github.arnavdugad.metropulse.core.domain.live.LiveArrival
import io.github.arnavdugad.metropulse.core.domain.live.LiveSnapshot
import io.github.arnavdugad.metropulse.core.domain.live.ServiceAlert
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Who provides live predictions for a city, as the app credits them. */
enum class LiveProvider(val credit: String) {
    Tfl("Powered by TfL Open Data"),
    Mta("Data from the MTA, which does not guarantee it is real time"),
}

/**
 * Live arrival predictions, fetched by the phone straight from the operator's free open feed: no
 * MetroPulse server, no key. London uses TfL's Unified API. New York's MTA feeds work the same
 * way, but the MTA's terms forbid apps that fetch from its servers directly, so they stay off
 * unless a build enables them ([AppBuildInfo.mtaRealtime]) with the MTA's permission.
 * No other city publishes a free realtime feed.
 */
@Singleton
class LiveRepository @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val info: AppBuildInfo,
) {
    fun provider(network: TransitNetwork): LiveProvider? = when {
        network.cityId == "london" && network.stationCodes.isNotEmpty() -> LiveProvider.Tfl
        network.cityId == "new-york" && info.mtaRealtime -> LiveProvider.Mta
        else -> null
    }

    /** Predictions for the station complex [complexId]; null if the city has no live source. */
    suspend fun forStation(network: TransitNetwork, complexId: String): LiveSnapshot? = when (provider(network)) {
        LiveProvider.Tfl -> {
            val members = network.complexes[complexId].orEmpty().mapTo(HashSet()) { it.id }
            val codes = network.stationCodes.filterValues { it in members }.keys
            if (codes.isEmpty()) null else tfl(network, "StopPoint/${codes.joinToString(",")}/Arrivals")
        }
        LiveProvider.Mta -> mta(network, network.linesAtComplex[complexId].orEmpty().map { it.id })
        null -> null
    }

    /** Predictions along one line, for following its trains. */
    suspend fun forLine(network: TransitNetwork, lineId: String): LiveSnapshot? = forLines(network, setOf(lineId))

    /** Predictions along several lines at once (one request to TfL), for the map. */
    suspend fun forLines(network: TransitNetwork, lineIds: Set<String>): LiveSnapshot? = when (provider(network)) {
        LiveProvider.Tfl -> lineIds.mapNotNull(::tflLineId).distinct().takeIf { it.isNotEmpty() }
            ?.let { ids -> tfl(network, "Line/${ids.joinToString(",")}/Arrivals") }
        LiveProvider.Mta -> mta(network, lineIds.toList())
        null -> null
    }

    /** Predictions at several station complexes at once (a journey's boarding and changing stations). */
    suspend fun forStations(network: TransitNetwork, complexIds: Set<String>): LiveSnapshot? = when (provider(network)) {
        LiveProvider.Tfl -> {
            val members = complexIds.flatMap { network.complexes[it].orEmpty() }.mapTo(HashSet()) { it.id }
            val codes = network.stationCodes.filterValues { it in members }.keys
            if (codes.isEmpty()) null else tfl(network, "StopPoint/${codes.joinToString(",")}/Arrivals")
        }
        LiveProvider.Mta -> mta(network, complexIds.flatMap { network.linesAtComplex[it].orEmpty() }.map { it.id }.distinct())
        null -> null
    }

    // --- Alerts ------------------------------------------------------------------------------

    private var alertCache: Pair<String, Pair<Long, List<ServiceAlert>>>? = null

    /** Current disruptions for the city (cached for [ALERTS_TTL_SECS]); empty if it has no source. */
    suspend fun alerts(network: TransitNetwork): List<ServiceAlert> {
        val provider = provider(network) ?: return emptyList()
        val now = Instant.now().epochSecond
        alertCache?.let { (city, cached) -> if (city == network.cityId && now - cached.first < ALERTS_TTL_SECS) return cached.second }
        val alerts = when (provider) {
            LiveProvider.Tfl -> tflAlerts(network)
            LiveProvider.Mta -> mtaAlerts(network)
        }.filter { it.activeAt(now) }
        alertCache = network.cityId to (now to alerts)
        return alerts
    }

    @Serializable
    private data class TflLine(val id: String = "", val name: String = "", val lineStatuses: List<TflStatus> = emptyList())

    @Serializable
    private data class TflStatus(
        val statusSeverity: Int = 10,
        val statusSeverityDescription: String = "",
        val reason: String? = null,
        val validityPeriods: List<TflPeriod> = emptyList(),
    )

    @Serializable
    private data class TflPeriod(val fromDate: String? = null, val toDate: String? = null)

    @Serializable
    private data class TflPoint(
        val atcoCode: String = "",
        val stationAtcoCode: String? = null,
        val commonName: String = "",
        val type: String = "",
        val description: String = "",
        val fromDate: String? = null,
        val toDate: String? = null,
    )

    private suspend fun tflAlerts(network: TransitNetwork): List<ServiceAlert> {
        val lines = json.decodeFromString<List<TflLine>>(get("$TFL_BASE/Line/Mode/tube,dlr/Status"))
        val out = ArrayList<ServiceAlert>()
        for (line in lines) {
            val ours = network.lines.map { it.id }.filter { tflLineId(it) == line.id }.toSet()
            if (ours.isEmpty()) continue
            // TfL can report one incident as two statuses ("Severe" and "Minor delays", same reason): keep the worst.
            val statuses = line.lineStatuses.filter { it.statusSeverity != GOOD_SERVICE && it.statusSeverity != NO_ISSUES }
                .sortedBy { tflSeverity(it.statusSeverity) }.distinctBy { it.reason?.trim() ?: it.statusSeverity.toString() }
            for (st in statuses) {
                // validityPeriods' isNow is unreliable; the dates are not.
                val period = st.validityPeriods.firstOrNull { p -> p.activeNow() } ?: st.validityPeriods.firstOrNull()
                out += ServiceAlert(
                    id = "tfl:${line.id}:${st.statusSeverity}",
                    severity = tflSeverity(st.statusSeverity),
                    title = "${line.name}: ${st.statusSeverityDescription}",
                    body = st.reason?.trim().orEmpty(),
                    lineIds = ours,
                    stationIds = emptySet(),
                    activeFrom = period?.fromDate?.let(::epoch),
                    activeTo = period?.toDate?.let(::epoch),
                )
            }
        }
        // Station closures and part closures (step-free outages and notices are left out: too many).
        runCatching { json.decodeFromString<List<TflPoint>>(get("$TFL_BASE/StopPoint/Mode/tube,dlr/Disruption")) }.getOrDefault(emptyList())
            .filter { it.type == "Closure" || it.type == "Part Closure" }
            .forEach { pt ->
                val station = network.stationCodes[pt.stationAtcoCode ?: pt.atcoCode] ?: network.stationCodes[pt.atcoCode] ?: return@forEach
                out += ServiceAlert(
                    id = "tfl:stop:${pt.atcoCode}:${pt.fromDate}",
                    severity = if (pt.type == "Closure") AlertSeverity.Closure else AlertSeverity.Minor,
                    title = "${network.stationById[station]?.name ?: pt.commonName}: ${pt.type.lowercase().replaceFirstChar { it.uppercase() }}",
                    body = pt.description.trim(),
                    lineIds = emptySet(),
                    stationIds = setOf(station),
                    activeFrom = pt.fromDate?.let(::epoch),
                    activeTo = pt.toDate?.let(::epoch),
                )
            }
        return out
    }

    private fun TflPeriod.activeNow(): Boolean {
        val now = Instant.now().epochSecond
        return (fromDate?.let(::epoch) ?: Long.MIN_VALUE) <= now && now < (toDate?.let(::epoch) ?: Long.MAX_VALUE)
    }

    private fun epoch(iso: String): Long? = runCatching { Instant.parse(if (iso.endsWith("Z")) iso else iso + "Z").epochSecond }.getOrNull()

    private fun tflSeverity(code: Int): AlertSeverity = when (code) {
        1, 2, 3, 4, 5, 11, 16, 20 -> AlertSeverity.Closure
        6, 7 -> AlertSeverity.Severe
        9, 14, 15, 17 -> AlertSeverity.Minor
        else -> AlertSeverity.Info
    }

    @Serializable
    private data class MtaAlerts(val entity: List<MtaEntity> = emptyList())

    @Serializable
    private data class MtaEntity(val id: String = "", val alert: MtaAlert? = null)

    @Serializable
    private data class MtaAlert(
        @SerialName("active_period") val activePeriod: List<MtaPeriod> = emptyList(),
        @SerialName("informed_entity") val informedEntity: List<MtaInformed> = emptyList(),
        @SerialName("header_text") val headerText: MtaText? = null,
        @SerialName("description_text") val descriptionText: MtaText? = null,
        @SerialName("transit_realtime.mercury_alert") val mercury: MtaMercury? = null,
    )

    @Serializable
    private data class MtaPeriod(val start: Long? = null, val end: Long? = null)

    @Serializable
    private data class MtaInformed(@SerialName("route_id") val routeId: String? = null, @SerialName("stop_id") val stopId: String? = null)

    @Serializable
    private data class MtaText(val translation: List<MtaTranslation> = emptyList()) {
        fun english(): String = (translation.firstOrNull { it.language == "en" } ?: translation.firstOrNull())?.text.orEmpty()
    }

    @Serializable
    private data class MtaTranslation(val text: String = "", val language: String? = null)

    @Serializable
    private data class MtaMercury(@SerialName("alert_type") val alertType: String? = null)

    private suspend fun mtaAlerts(network: TransitNetwork): List<ServiceAlert> {
        val feed = json.decodeFromString<MtaAlerts>(get("$MTA_BASE/camsys%2Fsubway-alerts.json"))
        val now = Instant.now().epochSecond
        return feed.entity.mapNotNull { e ->
            val a = e.alert ?: return@mapNotNull null
            val period = a.activePeriod.firstOrNull { (it.start ?: 0) <= now && (it.end == null || now < it.end) } ?: return@mapNotNull null
            val lines = a.informedEntity.mapNotNull { it.routeId?.let(network.routeCodes::get) }.toSet()
            val stations = a.informedEntity.mapNotNull { it.stopId?.takeIf(network.stationById::containsKey) }.toSet()
            if (lines.isEmpty() && stations.isEmpty()) return@mapNotNull null
            val type = a.mercury?.alertType.orEmpty()
            ServiceAlert(
                id = e.id,
                severity = when {
                    "Suspended" in type || "No " in type -> AlertSeverity.Closure
                    type == "Delays" || "Severe" in type -> AlertSeverity.Severe
                    type.isEmpty() || type == "Station Notice" || type == "Extra Service" -> AlertSeverity.Info
                    else -> AlertSeverity.Minor
                },
                title = type.ifEmpty { "Service notice" },
                body = a.headerText?.english().orEmpty(),
                lineIds = if (stations.isEmpty()) lines else emptySet(),
                stationIds = stations,
                activeFrom = period.start,
                activeTo = period.end,
            )
        }
    }

    // --- TfL ---------------------------------------------------------------------------------

    @Serializable
    private data class TflPrediction(
        val naptanId: String = "",
        val stationName: String = "",
        val lineId: String = "",
        val destinationNaptanId: String? = null,
        val destinationName: String? = null,
        val vehicleId: String? = null,
        val expectedArrival: String? = null,
        val platformName: String? = null,
    )

    private suspend fun tfl(network: TransitNetwork, path: String): LiveSnapshot {
        val body = get("$TFL_BASE/$path")
        val predictions = json.decodeFromString<List<TflPrediction>>(body)
        val byName by lazy { network.stations.groupBy { tflName(it.name) }.mapValues { it.value.first().id } }
        val arrivals = predictions.mapNotNull { p ->
            // Codes first; TfL's names ("West Ruislip Underground Station") for stations OSM gave none.
            val station = network.stationCodes[p.naptanId] ?: byName[tflName(p.stationName)] ?: return@mapNotNull null
            val destination = p.destinationNaptanId?.let(network.stationCodes::get) ?: p.destinationName?.let { byName[tflName(it)] }
            val line = ourLine(network, p.lineId, station, destination) ?: return@mapNotNull null
            val at = p.expectedArrival?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() } ?: return@mapNotNull null
            LiveArrival(
                line, station, at, vehicle = p.vehicleId?.takeIf { it.isNotBlank() && it != "000" }, destinationStationId = destination,
                platform = platformLabel(p.platformName),
            )
        }
        return LiveSnapshot(arrivals, Instant.now().epochSecond)
    }

    /** "Northbound - Platform 1" -> "Platform 1"; DLR's bare "4a" -> "Platform 4a"; directions alone -> null. */
    private fun platformLabel(name: String?): String? {
        val n = name?.trim().orEmpty()
        Regex("""(?i)platform\s*(\w+)""").find(n)?.let { return "Platform ${it.groupValues[1]}" }
        return if (Regex("""^\d+[a-z]?$""").matches(n)) "Platform $n" else null
    }

    /** "Ealing Broadway Underground Station" and "Ealing Broadway" both become "ealing broadway". */
    private fun tflName(name: String): String =
        name.lowercase().replace(Regex("""\(.*?\)"""), " ").replace(Regex("""\b(underground|dlr|rail)?\s*station\b"""), " ")
            .replace(Regex("[^a-z0-9&]+"), " ").trim()

    /** TfL's line id ("victoria", "dlr") for ours ("lon-victoria", "lon-dlr-bank-lewisham"). */
    private fun tflLineId(lineId: String): String? = when {
        lineId.startsWith("lon-dlr") -> "dlr"
        lineId.startsWith("lon-") -> lineId.removePrefix("lon-")
        else -> null
    }

    /** TfL runs the DLR as one line; MetroPulse has one per service, told apart by the stations. */
    private fun ourLine(network: TransitNetwork, tflLine: String, station: String, destination: String?): String? {
        val candidates = network.lines.map { it.id }.filter { tflLineId(it) == tflLine }
        if (candidates.size <= 1) return candidates.firstOrNull()
        return candidates.firstOrNull { line ->
            network.patterns.any { p -> p.lineId == line && station in p.stationIds && (destination == null || destination in p.stationIds) }
        }
    }

    // --- MTA ---------------------------------------------------------------------------------

    private suspend fun mta(network: TransitNetwork, lineIds: List<String>): LiveSnapshot = coroutineScope {
        val lineRoutes = network.routeCodes.entries.groupBy({ it.value }, { it.key })
        val feeds = lineIds.flatMap { lineRoutes[it].orEmpty() }.mapNotNull(::mtaFeed).distinct()
        val arrivals = feeds.map { feed ->
            async {
                val rt = GtfsRealtime.parse(getBytes("$MTA_BASE/$feed"))
                rt.trips.flatMap { trip ->
                    val line = network.routeCodes[trip.routeId] ?: return@flatMap emptyList()
                    val stops = trip.stops.filter { it.time != null }
                    val destination = stops.lastOrNull()?.let { parent(it.stopId) }
                    stops.mapIndexed { k, stop ->
                        LiveArrival(line, parent(stop.stopId), stop.time!!, vehicle = trip.tripId, destinationStationId = destination,
                            nextStationId = stops.getOrNull(k + 1)?.let { parent(it.stopId) })
                    }
                }
            }
        }.awaitAll().flatten()
        LiveSnapshot(arrivals.filter { network.stationById.containsKey(it.stationId) }, Instant.now().epochSecond)
    }

    /** Realtime stop ids are the static parent station plus a direction letter: A01N -> A01. */
    private fun parent(stopId: String) = if (stopId.length > 1 && stopId.last() in "NS") stopId.dropLast(1) else stopId

    private fun mtaFeed(routeId: String): String? = when (routeId) {
        "A", "C", "E", "H" -> "nyct%2Fgtfs-ace"
        "B", "D", "F", "FX", "M" -> "nyct%2Fgtfs-bdfm"
        "G" -> "nyct%2Fgtfs-g"
        "J", "Z" -> "nyct%2Fgtfs-jz"
        "N", "Q", "R", "W" -> "nyct%2Fgtfs-nqrw"
        "L" -> "nyct%2Fgtfs-l"
        "SI" -> "nyct%2Fgtfs-si"
        "1", "2", "3", "4", "5", "6", "6X", "7", "7X", "GS", "FS" -> "nyct%2Fgtfs"
        else -> null
    }

    // --- HTTP --------------------------------------------------------------------------------

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $url")
            r.body.string()
        }
    }

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $url")
            r.body.bytes()
        }
    }

    private companion object {
        const val TFL_BASE = "https://api.tfl.gov.uk"
        const val MTA_BASE = "https://api-endpoint.mta.info/Dataservice/mtagtfsfeeds"
        const val ALERTS_TTL_SECS = 120
        const val GOOD_SERVICE = 10
        const val NO_ISSUES = 18
    }
}
