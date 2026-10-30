package io.github.arnavdugad.metropulse.core.map

import android.view.Choreographer
import io.github.arnavdugad.metropulse.core.domain.live.LiveSnapshot
import io.github.arnavdugad.metropulse.core.domain.live.LiveTrains
import io.github.arnavdugad.metropulse.core.domain.transit.ScheduledTrains
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.exp

/** What the camera shows, for drawing detailed trains only where they can be seen. */
internal class Viewport(val bounds: LatLngBounds, val zoom: Double)

/** Where the trains on the map come from right now. */
enum class TrainSource { None, Scheduled, Live }

/**
 * Moves the trains along their lines: as dots at city zoom, and as 3D trains (see [TrainModel])
 * that ride the viaducts and ramps when zoomed in. Lines with live predictions show where the
 * operator says its trains are; the rest follow the timetable. Every train glides towards its
 * position, so a fresh prediction never makes it jump. Runs on frame callbacks, throttled; once a
 * second when the rider prefers reduced motion.
 */
internal class TrainAnimator(
    private val style: Style,
    private val data: NetworkMapData,
    private val reducedMotion: Boolean,
    private val viewport: () -> Viewport?,
    /** Called when the source of the trains on the map changes. */
    private val onSource: (TrainSource) -> Unit = {},
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(data.network.timezone) },
) : Choreographer.FrameCallback {

    private var running = false
    private var lastFrameNanos = 0L
    private var lastRenderNanos = 0L
    private var source: TrainSource? = null
    private var trains: ScheduledTrains? = null
    private var trainsDate: LocalDate? = null
    private val colours = data.network.lines.associate { it.id to NetworkMapData.hex(it.color) }
    private val bodies = data.network.lines.associate { it.id to NetworkMapData.hex(TrainModel.tint(it.color, BODY_TINT)) }

    private val placer = LiveTrains(data.network)
    private var live: List<LiveTrains.Train> = emptyList()
    private var liveLines: Set<String> = emptySet()

    /** Where each train was last drawn: key -> (pattern id, metres along). */
    private val drawn = HashMap<String, Pair<Int, Double>>()

    /** True when the city has trains to show (timetabled or live). */
    val hasTrains: Boolean get() = data.network.trips.isNotEmpty() || live.isNotEmpty()

    /** New live predictions; their lines stop following the timetable. */
    fun setLive(snapshot: LiveSnapshot?, lines: Set<String>) {
        live = snapshot?.let { placer.prepare(it, lines) }.orEmpty()
        liveLines = live.mapTo(HashSet()) { it.lineId }
        if (!running && hasTrains) start()
    }

    fun start() {
        if (running || !hasTrains) return
        running = true
        lastFrameNanos = 0L
        lastRenderNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val interval = if (reducedMotion) 1_000_000_000L else FRAME_INTERVAL_NANOS
        if (frameTimeNanos - lastFrameNanos >= interval) {
            val dt = if (lastRenderNanos == 0L) 0.0 else (frameTimeNanos - lastRenderNanos) / 1e9
            lastFrameNanos = frameTimeNanos
            lastRenderNanos = frameTimeNanos
            render(dt)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun render(dt: Double) {
        val t = now()
        val date = t.toLocalDate()
        if (date != trainsDate) {
            trains = ScheduledTrains(data.network, date)
            trainsDate = date
        }
        // (key, pattern, line, target metres)
        val targets = ArrayList<Target>()
        for (p in trains!!.positionsAt(t.toLocalTime().toSecondOfDay() + t.nano / 1e9)) {
            if (p.lineId !in liveLines) targets += Target("s${p.tripId}", p.patternId, p.lineId, p.alongM)
        }
        val nowEpoch = t.toEpochSecond() + t.nano / 1e9
        for (train in live) targets += Target("l${train.key}", train.pattern.id, train.lineId, placer.alongAt(train, nowEpoch))

        val newSource = when {
            live.isNotEmpty() -> TrainSource.Live
            targets.isNotEmpty() -> TrainSource.Scheduled
            else -> TrainSource.None
        }
        if (newSource != source) {
            source = newSource
            onSource(newSource)
        }

        val view = viewport()
        // Detailed trains only when zoomed in, and only near the visible area.
        val area = view?.takeIf { it.zoom >= DETAIL_ZOOM }?.bounds?.let(::expand)
        val above = ArrayList<Feature>()
        val tunnel = ArrayList<Feature>()
        val dots = ArrayList<Feature>(targets.size)
        val seen = HashSet<String>()
        // Glide: close a fixed share of the gap each second; a new prediction is reached in about half a second.
        val follow = if (reducedMotion || dt == 0.0) 1.0 else 1 - exp(-dt * GLIDE_RATE)
        for (target in targets) {
            val pattern = data.network.patternById[target.patternId] ?: continue
            val previous = drawn[target.key]
            val along = if (previous != null && previous.first == target.patternId && abs(previous.second - target.alongM) < MAX_GLIDE_M) {
                previous.second + (target.alongM - previous.second) * follow
            } else {
                target.alongM
            }
            drawn[target.key] = target.patternId to along
            seen += target.key
            val colour = colours[target.lineId] ?: "#888888"
            val head = pattern.shape.pointAt(along)
            dots += Feature.fromGeometry(Point.fromLngLat(head.lon, head.lat)).apply { addStringProperty("color", colour) }
            if (area != null && area.contains(LatLng(head.lat, head.lon))) {
                TrainModel.build(pattern.shape, data.elevation[target.patternId], along, colour, bodies[target.lineId] ?: "#DDDDDD", above, tunnel)
            }
        }
        drawn.keys.retainAll(seen)
        style.getSourceAs<GeoJsonSource>(MetroLayers.SRC_TRAINS)?.setGeoJson(FeatureCollection.fromFeatures(above))
        style.getSourceAs<GeoJsonSource>(MetroLayers.SRC_TRAINS_TUNNEL)?.setGeoJson(FeatureCollection.fromFeatures(tunnel))
        style.getSourceAs<GeoJsonSource>(MetroLayers.SRC_TRAIN_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dots))
    }

    private class Target(val key: String, val patternId: Int, val lineId: String, val alongM: Double)

    /** The visible area plus a margin, so trains entering the view are already built. */
    private fun expand(b: LatLngBounds): LatLngBounds {
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.25
        val dLon = (b.longitudeEast - b.longitudeWest) * 0.25
        return LatLngBounds.from(b.latitudeNorth + dLat, b.longitudeEast + dLon, b.latitudeSouth - dLat, b.longitudeWest - dLon)
    }

    private companion object {
        const val FRAME_INTERVAL_NANOS = 50_000_000L // ~20 updates a second; gliding keeps it smooth
        const val DETAIL_ZOOM = 13.0
        const val BODY_TINT = 0.62
        const val GLIDE_RATE = 4.0
        const val MAX_GLIDE_M = 2_500.0
    }
}
