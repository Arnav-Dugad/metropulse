package io.github.arnavdugad.metropulse.core.map

import android.view.Choreographer
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

/** What the camera shows, for drawing detailed trains only where they can be seen. */
internal class Viewport(val bounds: LatLngBounds, val zoom: Double)

/**
 * Moves every timetabled train along its line by the schedule: as dots at city zoom, and as 3D
 * trains (see [TrainModel]) that ride the viaducts and ramps when zoomed in. Runs on frame
 * callbacks, throttled; once a second when the rider prefers reduced motion.
 */
internal class TrainAnimator(
    private val style: Style,
    private val data: NetworkMapData,
    private val reducedMotion: Boolean,
    private val viewport: () -> Viewport?,
    /** Whether any train is on the map right now; called when that changes. */
    private val onRunning: (Boolean) -> Unit = {},
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(data.network.timezone) },
) : Choreographer.FrameCallback {

    private var trainsRunning: Boolean? = null

    private var running = false
    private var lastFrameNanos = 0L
    private var trains: ScheduledTrains? = null
    private var trainsDate: LocalDate? = null
    private val colours = data.network.lines.associate { it.id to NetworkMapData.hex(it.color) }
    private val bodies = data.network.lines.associate { it.id to NetworkMapData.hex(TrainModel.tint(it.color, BODY_TINT)) }

    /** True when the city has timetabled trains to show. */
    val hasTrains: Boolean get() = data.network.trips.isNotEmpty()

    fun start() {
        if (running || !hasTrains) return
        running = true
        lastFrameNanos = 0L
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
            lastFrameNanos = frameTimeNanos
            render()
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun render() {
        val t = now()
        val date = t.toLocalDate()
        if (date != trainsDate) {
            trains = ScheduledTrains(data.network, date)
            trainsDate = date
        }
        val positions = trains!!.positionsAt(t.toLocalTime().toSecondOfDay() + t.nano / 1e9)
        if (trainsRunning != positions.isNotEmpty()) {
            trainsRunning = positions.isNotEmpty()
            onRunning(positions.isNotEmpty())
        }
        val view = viewport()
        // Detailed trains only when zoomed in, and only near the visible area.
        val area = view?.takeIf { it.zoom >= DETAIL_ZOOM }?.bounds?.let(::expand)
        val above = ArrayList<Feature>()
        val tunnel = ArrayList<Feature>()
        val dots = ArrayList<Feature>(positions.size)
        for (pos in positions) {
            val pattern = data.network.patternById[pos.patternId] ?: continue
            val colour = colours[pos.lineId] ?: "#888888"
            val head = pattern.shape.pointAt(pos.alongM)
            dots += Feature.fromGeometry(Point.fromLngLat(head.lon, head.lat)).apply { addStringProperty("color", colour) }
            if (area != null && area.contains(LatLng(head.lat, head.lon))) {
                TrainModel.build(pattern.shape, data.elevation[pos.patternId], pos.alongM, colour, bodies[pos.lineId] ?: "#DDDDDD", above, tunnel)
            }
        }
        style.getSourceAs<GeoJsonSource>(MetroLayers.SRC_TRAINS)?.setGeoJson(FeatureCollection.fromFeatures(above))
        style.getSourceAs<GeoJsonSource>(MetroLayers.SRC_TRAINS_TUNNEL)?.setGeoJson(FeatureCollection.fromFeatures(tunnel))
        style.getSourceAs<GeoJsonSource>(MetroLayers.SRC_TRAIN_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dots))
    }

    /** The visible area plus a margin, so trains entering the view are already built. */
    private fun expand(b: LatLngBounds): LatLngBounds {
        val dLat = (b.latitudeNorth - b.latitudeSouth) * 0.25
        val dLon = (b.longitudeEast - b.longitudeWest) * 0.25
        return LatLngBounds.from(b.latitudeNorth + dLat, b.longitudeEast + dLon, b.latitudeSouth - dLat, b.longitudeWest - dLon)
    }

    private companion object {
        const val FRAME_INTERVAL_NANOS = 66_000_000L // ~15 updates a second is smooth at train speeds
        const val DETAIL_ZOOM = 13.0
        const val BODY_TINT = 0.62
    }
}
