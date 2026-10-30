package io.github.arnavdugad.metropulse.core.map

import android.content.Context
import android.view.Gravity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.withFrameNanos
import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import io.github.arnavdugad.metropulse.core.domain.live.LiveSnapshot
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.map.geometry.MapGeometry
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.LineString as GeoLineString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil

/** Starts MapLibre once per process. Tile requests go through the app's HTTP client (and User-Agent). */
object MetroMapInit {
    @Volatile private var initialised = false

    /** Parallel requests to the tile server; OpenFreeMap asks bulk users to stay at four. */
    private const val MAX_REQUESTS_PER_HOST = 4

    fun init(context: Context, httpClient: OkHttpClient) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            MapLibre.getInstance(context.applicationContext)
            val dispatcher = Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }
            HttpRequestUtil.setOkHttpClient(httpClient.newBuilder().dispatcher(dispatcher).build())
            initialised = true
        }
    }
}

/** A place to show: the camera flies there once (station details' "Show on map", for example). */
data class MapFocus(val lat: Double, val lon: Double, val zoom: Double = 16.0, val tilt: Double = 55.0, val bearing: Double = 0.0)

/** The rider's train, for follow mode: the camera rides along and the train is drawn in violet. */
data class MapFollow(val patternId: Int, val alongM: Double, val speedMps: Double?)

/** Camera tilt for the city overview, in degrees. */
private const val OVERVIEW_TILT = 52.0
private const val OVERVIEW_BEARING = -14.0
private const val FLY_MS = 1_600
private const val FLY_OVER_M = 1_500_000.0
private const val FLY_OVER_ZOOM = 2.2
private const val LIVE_ZOOM = 12.0
private const val ROUTE_TILT = 40.0
private const val ROUTE_DRAW_MS = 1_400.0
private const val TILT_ZOOM_BONUS = 0.15
private const val FOLLOW_ZOOM = 15.6
private const val FOLLOW_TILT = 58.0
private const val FOLLOW_EASE_MS = 950
private const val FOLLOW_PAUSE_MS = 15_000L
private const val LOOK_AHEAD_SECS = 8.0
private const val CRUISE_MPS = 9.0

/** Interpolates between two bearings the short way round. */
private fun lerpAngle(from: Double, to: Double, t: Double): Double {
    val delta = ((to - from + 540) % 360) - 180
    return (from + delta * t + 360) % 360
}

/**
 * The 3D network map. [network] null shows the base map only. [contentPadding] keeps the camera
 * framing and the compass clear of overlays (header, tab bar).
 */
@Composable
fun MetroMap(
    network: TransitNetwork?,
    dark: Boolean,
    reducedMotion: Boolean,
    contentPadding: PaddingValues,
    onStationClick: (complexId: String) -> Unit,
    onMapClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where the trains on the map come from right now (none, the timetable, live predictions). */
    onTrains: (TrainSource) -> Unit = {},
    follow: MapFollow? = null,
    focus: MapFocus? = null,
    onFocusShown: () -> Unit = {},
    /** Live predictions for the lines last asked for through [onLiveLinesWanted]. */
    live: LiveSnapshot? = null,
    /** Lines on screen when zoomed in enough to follow trains; empty when zoomed out. */
    onLiveLinesWanted: (Set<String>) -> Unit = {},
    onStationLongPress: (complexId: String) -> Unit = {},
    /** A planned journey to draw (it traces itself across the map), or null. */
    route: MapRoute? = null,
    /** Stops animating trains while a sheet covers the map. */
    paused: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Texture mode puts the map in the normal view hierarchy, so the frosted-glass overlays can blur it.
    val mapView = remember { MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var data by remember { mutableStateOf<NetworkMapData?>(null) }
    // Survives tab switches (the navigation back stack saves it), so returning to the map does not re-fly.
    var framedCity by rememberSaveable { mutableStateOf<String?>(null) }
    var savedCamera by rememberSaveable { mutableStateOf<DoubleArray?>(null) }
    // Follow mode pauses for a while after the rider pans the map themselves.
    var userMovedAt by remember { mutableLongStateOf(0L) }
    var followBearing by remember { mutableStateOf<Double?>(null) }
    val stationClick by rememberUpdatedState(onStationClick)
    val mapClick by rememberUpdatedState(onMapClick)
    val stationLongPress by rememberUpdatedState(onStationLongPress)
    val liveLinesWanted by rememberUpdatedState(onLiveLinesWanted)
    var animator by remember { mutableStateOf<TrainAnimator?>(null) }

    val padding = with(density) {
        intArrayOf(
            contentPadding.calculateLeftPadding(layoutDirection).roundToPx(),
            contentPadding.calculateTopPadding().roundToPx(),
            contentPadding.calculateRightPadding(layoutDirection).roundToPx(),
            contentPadding.calculateBottomPadding().roundToPx(),
        )
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isLogoEnabled = false
                isAttributionEnabled = false // shown by the app, always visible
                isCompassEnabled = true
                setCompassFadeFacingNorth(true)
                compassGravity = Gravity.TOP or Gravity.END
                setCompassMargins(0, padding[1] + 16, padding[2] + 16, 0)
            }
            savedCamera?.let { (lat, lon, zoom, tilt, bearing) ->
                m.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(lat, lon)).zoom(zoom).tilt(tilt).bearing(bearing).build()))
            }
            m.addOnCameraIdleListener {
                val c = m.cameraPosition
                c.target?.let { savedCamera = doubleArrayOf(it.latitude, it.longitude, c.zoom, c.tilt, c.bearing) }
                // Live trains are fetched only for the lines in view, and only when close enough to see them.
                val d = data
                val visible = m.projection.visibleRegion.latLngBounds
                liveLinesWanted(
                    if (d == null || c.zoom < LIVE_ZOOM) emptySet()
                    else d.lineBounds.filterValues { it.intersect(visible) != null }.keys,
                )
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) userMovedAt = System.currentTimeMillis()
            }
            m.addOnMapClickListener { latLng ->
                val hits = m.queryRenderedFeatures(m.projection.toScreenLocation(latLng), MetroLayers.LAYER_STATIONS)
                val complex = hits.firstOrNull()?.getStringProperty("complex")
                if (complex != null) stationClick(complex) else mapClick()
                complex != null
            }
            m.addOnMapLongClickListener { latLng ->
                val hits = m.queryRenderedFeatures(m.projection.toScreenLocation(latLng), MetroLayers.LAYER_STATIONS)
                val complex = hits.firstOrNull()?.getStringProperty("complex") ?: return@addOnMapLongClickListener false
                stationLongPress(complex)
                true
            }
            map = m
        }
    }

    LaunchedEffect(map, dark) {
        val m = map ?: return@LaunchedEffect
        style = null
        m.setStyle(Style.Builder().fromUri("asset://map/pulse-${if (dark) "dark" else "light"}.json")) { loaded ->
            MetroLayers.install(loaded, MapPalette(dark), density.density)
            style = loaded
        }
    }

    LaunchedEffect(network) {
        data = network?.let { withContext(Dispatchers.Default) { NetworkMapData(it) } }
    }

    LaunchedEffect(style, data) {
        val s = style ?: return@LaunchedEffect
        val d = data ?: return@LaunchedEffect
        MetroLayers.setNetwork(s, d)
        val m = map ?: return@LaunchedEffect
        if (framedCity != d.network.cityId) {
            // Fly when switching cities; jump straight there the first time.
            val animate = framedCity != null && !reducedMotion
            framedCity = d.network.cityId
            d.bounds?.let { bounds ->
                // Fit the network flat (but already rotated), then tilt: tilting brings the near half
                // closer, so zoom in a little.
                val flat = m.getCameraForLatLngBounds(bounds, padding, OVERVIEW_BEARING, 0.0)
                if (flat != null) {
                    val camera = CameraPosition.Builder(flat).zoom(flat.zoom + TILT_ZOOM_BONUS).tilt(OVERVIEW_TILT).bearing(OVERVIEW_BEARING).build()
                    val from = m.cameraPosition.target
                    val to = camera.target
                    val far = from != null && to != null && from.distanceTo(to) > FLY_OVER_M
                    when {
                        !animate -> m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
                        // Between continents, rise until the whole route is in view, then come down on the new city.
                        far -> {
                            val middle = LatLng((from!!.latitude + to!!.latitude) / 2, midLongitude(from.longitude, to.longitude))
                            val high = CameraPosition.Builder().target(middle).zoom(FLY_OVER_ZOOM).tilt(0.0).bearing(0.0).build()
                            m.animateCamera(CameraUpdateFactory.newCameraPosition(high), FLY_MS, object : MapLibreMap.CancelableCallback {
                                override fun onFinish() = m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), FLY_MS)
                                override fun onCancel() = Unit
                            })
                        }
                        else -> m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), FLY_MS)
                    }
                }
            }
        }
    }

    LaunchedEffect(map, data, focus) {
        val m = map ?: return@LaunchedEffect
        val f = focus ?: return@LaunchedEffect
        // Wait for the city: framing it would otherwise replace the requested view.
        framedCity = (data ?: return@LaunchedEffect).network.cityId
        val camera = CameraPosition.Builder().target(LatLng(f.lat, f.lon)).zoom(f.zoom).tilt(f.tilt).bearing(f.bearing).build()
        if (reducedMotion) m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
        else m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), FLY_MS)
        onFocusShown()
    }

    LaunchedEffect(style, data, follow) {
        val s = style ?: return@LaunchedEffect
        val d = data ?: return@LaunchedEffect
        MetroLayers.setMyTrain(s, d, follow, MapPalette(dark))
        val m = map ?: return@LaunchedEffect
        if (follow == null) {
            followBearing = null
            return@LaunchedEffect
        }
        if (System.currentTimeMillis() - userMovedAt < FOLLOW_PAUSE_MS) return@LaunchedEffect
        val shape = d.network.patternById[follow.patternId]?.shape ?: return@LaunchedEffect
        // Look ahead of the train in proportion to its speed, and turn the map gently.
        val ahead = ((follow.speedMps ?: CRUISE_MPS) * LOOK_AHEAD_SECS).coerceIn(40.0, 260.0)
        val target = shape.pointAt(follow.alongM + ahead)
        val wanted = shape.bearingAt(follow.alongM + 30)
        val bearing = followBearing?.let { lerpAngle(it, wanted, 0.3) } ?: wanted
        val first = followBearing == null
        followBearing = bearing
        val camera = CameraPosition.Builder().target(LatLng(target.lat, target.lon)).zoom(FOLLOW_ZOOM).tilt(FOLLOW_TILT).bearing(bearing).build()
        when {
            reducedMotion -> m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
            first -> m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), FLY_MS)
            else -> m.easeCamera(CameraUpdateFactory.newCameraPosition(camera), FOLLOW_EASE_MS)
        }
    }

    val trainSource by rememberUpdatedState(onTrains)
    val isPaused by rememberUpdatedState(paused)
    DisposableEffect(style, data, reducedMotion) {
        val s = style
        val d = data
        val a = if (s != null && d != null) {
            TrainAnimator(s, d, reducedMotion, viewport = {
                map?.let { m -> Viewport(m.projection.visibleRegion.latLngBounds, m.cameraPosition.zoom) }
            }, onSource = { trainSource(it) })
        } else {
            null
        }
        animator = a
        trainSource(TrainSource.None) // until the first frame finds trains running
        if (!isPaused) a?.start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!isPaused) a?.start()
                Lifecycle.Event.ON_PAUSE -> a?.stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            a?.stop()
            animator = null
        }
    }

    // A sheet over the map: nobody can see the trains, so stop moving them (keeps scrolling smooth).
    LaunchedEffect(animator, paused) {
        val a = animator ?: return@LaunchedEffect
        if (paused) a.stop() else a.start()
    }

    LaunchedEffect(animator, live) {
        val a = animator ?: return@LaunchedEffect
        a.setLive(live, live?.arrivals?.mapTo(HashSet()) { it.lineId }.orEmpty())
    }

    // The planned journey draws itself from start to end, then the camera frames it.
    LaunchedEffect(style, route) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        val r = route
        if (r == null) {
            MetroLayers.setRoute(s, FeatureCollection.fromFeatures(emptyList()))
            return@LaunchedEffect
        }
        val all = r.segments.flatMap { it.points }
        if (all.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(all.map { LatLng(it.lat, it.lon) }).build()
            m.getCameraForLatLngBounds(bounds, padding, OVERVIEW_BEARING, 0.0)?.let { flat ->
                val camera = CameraPosition.Builder(flat).zoom(flat.zoom + TILT_ZOOM_BONUS).tilt(ROUTE_TILT).bearing(OVERVIEW_BEARING).build()
                if (reducedMotion) m.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
                else m.animateCamera(CameraUpdateFactory.newCameraPosition(camera), FLY_MS)
            }
        }
        val lengths = r.segments.map { LineString(it.points).lengthM }
        val total = lengths.sum().coerceAtLeast(1.0)
        val start = System.nanoTime()
        while (true) {
            val f = if (reducedMotion) 1.0 else ((System.nanoTime() - start) / 1e6 / ROUTE_DRAW_MS).coerceAtMost(1.0)
            MetroLayers.setRoute(s, routeFeatures(r, lengths, total * easeInOut(f)))
            if (f >= 1.0) break
            withFrameNanos { }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** The route drawn up to [lengthM] metres from its start. */
private fun routeFeatures(route: MapRoute, lengths: List<Double>, lengthM: Double): FeatureCollection {
    val features = ArrayList<Feature>()
    var left = lengthM
    route.segments.forEachIndexed { i, segment ->
        if (left <= 0) return@forEachIndexed
        val line = LineString(segment.points)
        val points = if (left >= lengths[i]) segment.points else MapGeometry.slice(line, 0.0, left)
        left -= lengths[i]
        if (points.size >= 2) {
            features += Feature.fromGeometry(GeoLineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty("color", NetworkMapData.hex(segment.color))
                addBooleanProperty("walk", segment.walk)
            }
        }
    }
    return FeatureCollection.fromFeatures(features)
}

private fun easeInOut(t: Double): Double = if (t < 0.5) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

/** The longitude halfway between two, the short way round the globe. */
private fun midLongitude(a: Double, b: Double): Double {
    var d = b - a
    if (d > 180) d -= 360
    if (d < -180) d += 360
    return ((a + d / 2 + 540) % 360) - 180
}
