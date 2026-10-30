package io.github.arnavdugad.metropulse.core.map

import android.content.Context
import android.util.Log
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

/** A city's downloaded map, as Settings and the map show it. */
sealed interface OfflineMapState {
    data object None : OfflineMapState
    data class Downloading(val fraction: Float, val bytes: Long) : OfflineMapState
    data class Ready(val bytes: Long) : OfflineMapState
    data object Failed : OfflineMapState
}

/** What a city download would contain: zoom levels and tile count. */
data class OfflinePlan(val minZoom: Int, val maxZoom: Int, val tiles: Long)

/**
 * Map downloads for offline use, one region per city, through MapLibre's offline database. Kept
 * within OpenFreeMap's guidance for bulk use: at most [MAX_TILES] tiles per city, and the map's HTTP
 * client allows only a few parallel requests (see [MetroMapInit]). Vector tiles at zoom 14 carry
 * every detail the style draws, so a city that fits at 14 is complete offline, 3D buildings included.
 */
object OfflineMaps {
    const val MAX_TILES = 3_000L
    private const val TAG = "OfflineMaps"
    private const val MIN_ZOOM = 5
    private const val DETAIL_ZOOM = 14
    // The offline downloader cannot read asset:// URLs, so the bundled style is stored in the offline
    // database under a reserved (.invalid) URL first. Both styles share tiles, sprites and glyphs.
    private const val STYLE_ASSET = "map/pulse-light.json"
    private const val STYLE_URL = "https://metropulse.invalid/styles/pulse-light.json"
    private const val TEN_YEARS_SECS = 10L * 365 * 24 * 3600

    private val _states = MutableStateFlow<Map<String, OfflineMapState>>(emptyMap())
    val states: StateFlow<Map<String, OfflineMapState>> = _states.asStateFlow()

    private val regions = HashMap<String, OfflineRegion>()
    private var loaded = false

    /** The largest zoom (up to full detail) whose tile pyramid over [bbox] stays within the limit. */
    fun plan(bbox: List<Double>): OfflinePlan {
        var best = OfflinePlan(MIN_ZOOM, MIN_ZOOM, tileCount(bbox, MIN_ZOOM, MIN_ZOOM))
        for (z in MIN_ZOOM..DETAIL_ZOOM) {
            val tiles = tileCount(bbox, MIN_ZOOM, z)
            if (tiles > MAX_TILES) break
            best = OfflinePlan(MIN_ZOOM, z, tiles)
        }
        return best
    }

    /** Tiles covering [bbox] (south, west, north, east) from [minZoom] to [maxZoom], inclusive. */
    fun tileCount(bbox: List<Double>, minZoom: Int, maxZoom: Int): Long {
        val (south, west, north, east) = bbox
        return (minZoom..maxZoom).sumOf { z ->
            val xs = tileX(east, z) - tileX(west, z) + 1
            val ys = tileY(south, z) - tileY(north, z) + 1
            xs.toLong() * ys
        }
    }

    private fun tileX(lon: Double, z: Int) = floor((lon + 180) / 360 * 2.0.pow(z)).toInt()

    private fun tileY(lat: Double, z: Int): Int {
        val r = lat * PI / 180
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 2.0.pow(z)).toInt()
    }

    /** Reads which cities are already downloaded (once per process). */
    fun refresh(context: Context) {
        if (loaded) return
        loaded = true
        OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                offlineRegions.orEmpty().forEach { region ->
                    val city = String(region.metadata)
                    regions[city] = region
                    region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            status ?: return
                            set(city, if (status.isComplete) OfflineMapState.Ready(status.completedResourceSize) else OfflineMapState.Failed)
                        }

                        override fun onError(error: String?) = set(city, OfflineMapState.Failed)
                    })
                }
            }

            override fun onError(error: String) {
                loaded = false
            }
        })
    }

    fun download(context: Context, cityId: String, bbox: List<Double>) {
        if (_states.value[cityId] is OfflineMapState.Downloading) return
        val plan = plan(bbox)
        val (south, west, north, east) = bbox
        val definition = OfflineTilePyramidRegionDefinition(
            STYLE_URL,
            LatLngBounds.from(north, east, south, west),
            plan.minZoom.toDouble(),
            plan.maxZoom.toDouble(),
            context.resources.displayMetrics.density,
            false,
        )
        set(cityId, OfflineMapState.Downloading(0f, 0))
        // Replace any earlier, incomplete download of the same city.
        regions.remove(cityId)?.delete(noopDelete)
        val manager = OfflineManager.getInstance(context)
        val style = context.assets.open(STYLE_ASSET).use { it.readBytes() }
        val now = System.currentTimeMillis() / 1000
        manager.putResourceWithUrl(STYLE_URL, style, now, now + TEN_YEARS_SECS, style.contentHashCode().toString(), false)
        manager.createOfflineRegion(definition, cityId.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                regions[cityId] = offlineRegion
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        if (status.isComplete) {
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            set(cityId, OfflineMapState.Ready(status.completedResourceSize))
                        } else {
                            val required = status.requiredResourceCount.coerceAtLeast(1)
                            set(cityId, OfflineMapState.Downloading(status.completedResourceCount.toFloat() / required, status.completedResourceSize))
                        }
                    }

                    // Individual failed requests are retried by MapLibre, so this is only logged.
                    override fun onError(error: OfflineRegionError) {
                        Log.w(TAG, "offline map $cityId: ${error.reason} ${error.message}")
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        set(cityId, OfflineMapState.Failed)
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) = set(cityId, OfflineMapState.Failed)
        })
    }

    fun cancel(cityId: String) {
        regions.remove(cityId)?.let {
            it.setDownloadState(OfflineRegion.STATE_INACTIVE)
            it.delete(noopDelete)
        }
        set(cityId, OfflineMapState.None)
    }

    fun delete(cityId: String) = cancel(cityId)

    private fun set(cityId: String, state: OfflineMapState) = _states.update { it + (cityId to state) }

    private val noopDelete = object : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit
        override fun onError(error: String) = Unit
    }
}
