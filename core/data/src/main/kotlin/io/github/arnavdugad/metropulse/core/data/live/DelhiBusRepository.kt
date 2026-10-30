package io.github.arnavdugad.metropulse.core.data.live

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.live.BusRoute
import io.github.arnavdugad.metropulse.core.domain.live.BusSnapshot
import io.github.arnavdugad.metropulse.core.domain.live.NearbyBuses
import io.github.arnavdugad.metropulse.core.domain.live.RtPosition
import io.github.arnavdugad.metropulse.core.domain.live.VehiclePositions
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delhi's live bus positions (DTC and cluster buses) from the Transport Department's Open Transit
 * Data feed. The phone downloads the whole city's feed (about 600 KB) and picks out the buses near a
 * station itself, so its location is never sent. One download serves every screen for [TTL_SECS].
 * Route names come from the static routes bundled in assets/live/delhi-bus-routes.json; without
 * them a bus shows its registration.
 */
@Singleton
class DelhiBusRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
    private val info: AppBuildInfo,
) {
    private val lock = Mutex()
    private var fetchedAt = 0L
    private var etag: String? = null
    private var current: List<RtPosition> = emptyList()
    private var previous: Map<String, LatLon> = emptyMap()

    private val routes: Map<String, BusRoute> by lazy {
        runCatching {
            val obj = JSONObject(context.assets.open(ROUTES_ASSET).bufferedReader().use { it.readText() }).getJSONObject("routes")
            obj.keys().asSequence().associateWith { id ->
                val r = obj.getJSONArray(id)
                BusRoute(r.getString(0), r.optString(1).takeIf { it.isNotBlank() })
            }
        }.getOrDefault(emptyMap())
    }

    fun available(network: TransitNetwork): Boolean = network.cityId == CITY && info.otdDelhiKey.isNotBlank()

    /** Buses within [radiusM] of the station complex [complexId]; null where there is no feed. */
    suspend fun near(network: TransitNetwork, complexId: String, radiusM: Int = RADIUS_M): BusSnapshot? {
        if (!available(network)) return null
        val points = network.complexes[complexId].orEmpty().map { it.location } +
            network.exits.filter { e -> network.complexes[complexId].orEmpty().any { it.id == e.stationId } }.map { it.location }
        val (positions, before, at) = positions()
        val now = Instant.now().epochSecond
        return BusSnapshot(NearbyBuses.near(positions, points, routes, now, radiusM, MAX_AGE_SECS, before), at, radiusM)
    }

    private suspend fun positions(): Triple<List<RtPosition>, Map<String, LatLon>, Long> = lock.withLock {
        val now = Instant.now().epochSecond
        if (now - fetchedAt >= TTL_SECS) {
            val fresh = fetch()
            if (fresh != null) {
                previous = current.associate { it.vehicleId to it.location }
                current = fresh
            }
            fetchedAt = now
        }
        Triple(current, previous, fetchedAt)
    }

    /** The feed, or null when it has not changed since the last download (HTTP 304). */
    private suspend fun fetch(): List<RtPosition>? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$FEED_URL?key=${info.otdDelhiKey}")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()
        client.newCall(request).execute().use { r ->
            if (r.code == 304) return@withContext null
            // The URL carries the key, so errors name the host only.
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} from otd.delhi.gov.in")
            etag = r.header("ETag")
            VehiclePositions.parse(r.body.bytes())
        }
    }

    private companion object {
        const val CITY = "delhi"
        const val FEED_URL = "https://otd.delhi.gov.in/api/realtime/VehiclePositions.pb"
        const val ROUTES_ASSET = "live/delhi-bus-routes.json"
        const val TTL_SECS = 25
        const val RADIUS_M = 600
        const val MAX_AGE_SECS = 180
    }
}
