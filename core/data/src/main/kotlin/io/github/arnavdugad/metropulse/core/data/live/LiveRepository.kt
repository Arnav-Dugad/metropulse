package io.github.arnavdugad.metropulse.core.data.live

import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.domain.live.GtfsRealtime
import io.github.arnavdugad.metropulse.core.domain.live.LiveArrival
import io.github.arnavdugad.metropulse.core.domain.live.LiveSnapshot
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
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
    suspend fun forLine(network: TransitNetwork, lineId: String): LiveSnapshot? = when (provider(network)) {
        LiveProvider.Tfl -> tflLineId(lineId)?.let { tfl(network, "Line/$it/Arrivals") }
        LiveProvider.Mta -> mta(network, listOf(lineId))
        null -> null
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
            LiveArrival(line, station, at, vehicle = p.vehicleId?.takeIf { it.isNotBlank() && it != "000" }, destinationStationId = destination)
        }
        return LiveSnapshot(arrivals, Instant.now().epochSecond)
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
    }
}
