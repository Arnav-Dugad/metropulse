package io.github.arnavdugad.metropulse.core.map

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.TrackKind
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.map.geometry.ElevationProfile
import io.github.arnavdugad.metropulse.core.map.geometry.KindSpan
import io.github.arnavdugad.metropulse.core.map.geometry.MapGeometry
import io.github.arnavdugad.metropulse.core.map.geometry.Ring
import io.github.arnavdugad.metropulse.core.map.geometry.TrackProfile
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.floor

/** Dimensions (metres) of the 3D track structures. Viaducts sit a little above typical real decks so they read at city zooms. */
internal object Structure {
    const val DECK_THICKNESS = 1.6
    const val DECK_WIDTH = 9.6
    const val PARAPET_WIDTH = 0.45
    const val PARAPET_HEIGHT = 1.1
    const val BED_WIDTH = 4.4
    const val BED_HEIGHT = 0.3
    const val PIER_SPACING = 34.0
    const val PIER_SIZE = 2.1
    const val CAP_ALONG = 2.4
    const val CAP_ACROSS = 7.2
    const val CAP_HEIGHT = 1.4
    /** Below this the ramp is drawn as a solid embankment rather than a deck on piers. */
    const val EMBANKMENT_BELOW = 5.0
    const val TRENCH_WIDTH = 11.0
    const val WALL_WIDTH = 0.7
    const val WALL_HEIGHT = 2.4
    const val PORTAL_LENGTH = 12.0
    const val PORTAL_HEIGHT = 6.6
    const val PORTAL_SLAB = 1.3
}

/** The metro network as map features. Built once per city, off the main thread. */
class NetworkMapData(val network: TransitNetwork) {
    private val profile = TrackProfile(network.tracks)

    /** Track kind along every pattern. */
    val spans: Map<Int, List<KindSpan>> = network.patterns.associate { it.id to profile.spans(it) }

    /** Track height along every pattern, shared by the structures and the trains running on them. */
    val elevation: Map<Int, ElevationProfile> = network.patterns.associate { p -> p.id to ElevationProfile(spans.getValue(p.id), p.shape.lengthM) }

    /**
     * Patterns drawn as coloured lines: one direction per line, branches included, leaving out
     * variants that run entirely over the line's own track (NYC has 261 patterns on 29 lines).
     */
    private val drawn: List<Pattern> = network.patterns.groupBy { it.lineId }.values.flatMap { patterns ->
        distinctTrack(patterns.filter { it.direction == 0 }.ifEmpty { patterns }, TrackCoverage())
    }

    /** Patterns that get viaducts and cuttings: each stretch of track once, however many lines share it. */
    private val built: Set<Int> = distinctTrack(drawn, TrackCoverage()).mapTo(HashSet()) { it.id }

    val lines: FeatureCollection

    /** Every static 3D part (decks, parapets, piers, cutting walls, portals), tagged by "part". */
    val structures: FeatureCollection

    /** Cutting floors, drawn flat and dark where the line descends into a tunnel. */
    val trenches: FeatureCollection
    val stations: FeatureCollection
    val bounds: LatLngBounds?

    init {
        val lineFeatures = ArrayList<Feature>()
        val parts = ArrayList<Feature>()
        val floors = ArrayList<Feature>()
        for (p in drawn) {
            val line = network.lineById[p.lineId] ?: continue
            val colour = hex(line.color)
            for (span in spans.getValue(p.id)) {
                val part = MapGeometry.slice(p.shape, span.fromM, span.toM)
                if (part.size < 2) continue
                lineFeatures += Feature.fromGeometry(LineString.fromLngLats(part.map(::point))).apply {
                    addStringProperty("color", colour)
                    addStringProperty("kind", span.kind.key)
                    addNumberProperty("sort", line.sortOrder)
                }
            }
            if (p.id in built) buildStructures(p, elevation.getValue(p.id), colour, parts, floors)
        }
        lines = FeatureCollection.fromFeatures(lineFeatures)
        structures = FeatureCollection.fromFeatures(parts)
        trenches = FeatureCollection.fromFeatures(floors)

        stations = FeatureCollection.fromFeatures(
            network.complexes.map { (complexId, members) ->
                val lat = members.sumOf { it.location.lat } / members.size
                val lon = members.sumOf { it.location.lon } / members.size
                val lines = network.linesAtComplex[complexId].orEmpty()
                Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                    addStringProperty("complex", complexId)
                    addStringProperty("name", members.first().name)
                    addBooleanProperty("interchange", lines.size > 1)
                    addStringProperty("color", lines.firstOrNull()?.let { hex(it.color) } ?: "#888888")
                }
            },
        )
        bounds = network.stations.takeIf { it.size >= 2 }?.let { list ->
            LatLngBounds.Builder().includes(list.map { LatLng(it.location.lat, it.location.lon) }).build()
        }
    }

    private fun buildStructures(p: Pattern, elev: ElevationProfile, colour: String, parts: MutableList<Feature>, floors: MutableList<Feature>) {
        val s = Structure
        fun add(rings: List<Ring>, part: String, base: Double, top: Double) {
            for (ring in rings) parts += polygon(ring).apply {
                addStringProperty("part", part)
                addStringProperty("color", colour)
                addNumberProperty("base", base)
                addNumberProperty("top", top)
            }
        }
        // Viaducts and ramps: deck (or embankment near the ground), parapets and the coloured track bed.
        for (piece in elev.raisedPieces()) {
            val line = MapGeometry.slice(p.shape, piece.fromM, piece.toM)
            if (line.size < 2) continue
            val top = piece.top
            val embankment = top < s.EMBANKMENT_BELOW
            // Flush ends: a ramp step overhanging its lower neighbour would hide that step's track bed.
            fun strip(width: Double, offset: Double = 0.0) = MapGeometry.strip(line, width, offset, extendEnds = false)
            add(strip(s.DECK_WIDTH), if (embankment) "embankment" else "deck", if (embankment) 0.0 else (top - s.DECK_THICKNESS).coerceAtLeast(0.0), top)
            val edge = s.DECK_WIDTH / 2 - s.PARAPET_WIDTH / 2
            add(strip(s.PARAPET_WIDTH, edge), "parapet", top, top + s.PARAPET_HEIGHT)
            add(strip(s.PARAPET_WIDTH, -edge), "parapet", top, top + s.PARAPET_HEIGHT)
            add(strip(s.BED_WIDTH), "bed", top, top + s.BED_HEIGHT)
        }
        // T-shaped piers wherever the deck is high enough to stand on them.
        var d = s.PIER_SPACING / 2
        while (d < p.shape.lengthM) {
            val top = elev.top(d)
            if (top >= s.EMBANKMENT_BELOW) {
                val at = p.shape.pointAt(d)
                val bearing = p.shape.bearingAt(d)
                val capTop = top - s.DECK_THICKNESS
                add(listOf(MapGeometry.orientedRect(at, bearing, s.PIER_SIZE, s.PIER_SIZE)), "pier", 0.0, capTop - s.CAP_HEIGHT)
                add(listOf(MapGeometry.orientedRect(at, bearing, s.CAP_ALONG, s.CAP_ACROSS)), "cap", capTop - s.CAP_HEIGHT, capTop)
            }
            d += s.PIER_SPACING
        }
        // Cuttings: a dark floor and retaining walls that rise towards the tunnel mouth.
        for (t in elev.trenches) {
            val floor = MapGeometry.slice(p.shape, t.fromM, t.toM)
            if (floor.size < 2) continue
            MapGeometry.strip(floor, s.TRENCH_WIDTH).mapTo(floors) { polygon(it) }
            var a = t.fromM
            while (a < t.toM) {
                val b = minOf(a + ElevationProfile.STEP_M, t.toM)
                val f = ((a + b) / 2 - t.fromM) / (t.toM - t.fromM)
                val height = s.WALL_HEIGHT * (if (t.deepEndAtTo) f else 1 - f).coerceIn(0.15, 1.0)
                val seg = MapGeometry.slice(p.shape, a, b)
                val off = s.TRENCH_WIDTH / 2
                add(MapGeometry.strip(seg, s.WALL_WIDTH, off), "wall", 0.0, height)
                add(MapGeometry.strip(seg, s.WALL_WIDTH, -off), "wall", 0.0, height)
                a = b
            }
        }
        // Tunnel portals: a concrete box over the track just inside the tunnel.
        for (portal in elev.portals) {
            val (a, b) = if (portal.tunnelAhead) portal.atM to portal.atM + s.PORTAL_LENGTH else portal.atM - s.PORTAL_LENGTH to portal.atM
            val seg = MapGeometry.slice(p.shape, a, b)
            if (seg.size < 2) continue
            val off = s.TRENCH_WIDTH / 2
            add(MapGeometry.strip(seg, s.WALL_WIDTH * 1.6, off), "portal", 0.0, s.PORTAL_HEIGHT)
            add(MapGeometry.strip(seg, s.WALL_WIDTH * 1.6, -off), "portal", 0.0, s.PORTAL_HEIGHT)
            add(MapGeometry.strip(seg, s.TRENCH_WIDTH + s.WALL_WIDTH * 1.6), "portal", s.PORTAL_HEIGHT - s.PORTAL_SLAB, s.PORTAL_HEIGHT)
        }
    }

    fun spanKind(patternId: Int, alongM: Double): TrackKind =
        spans[patternId]?.firstOrNull { alongM >= it.fromM && alongM <= it.toM }?.kind ?: TrackKind.AtGrade

    internal companion object {
        fun hex(rgb: Int) = "#%06X".format(rgb and 0xFFFFFF)
        fun point(p: LatLon): Point = Point.fromLngLat(p.lon, p.lat)
        fun polygon(ring: Ring): Feature = Feature.fromGeometry(Polygon.fromLngLats(listOf(ring.map(::point))))
    }
}

internal val TrackKind.key: String
    get() = when (this) {
        TrackKind.Elevated -> "elevated"
        TrackKind.Underground -> "underground"
        TrackKind.AtGrade -> "at_grade"
    }

/** Longest first, keeping each pattern that adds track not already in [coverage]. */
private fun distinctTrack(patterns: List<Pattern>, coverage: TrackCoverage): List<Pattern> =
    patterns.sortedByDescending { it.shape.lengthM }.filter { p ->
        val samples = (0..(p.shape.lengthM / SAMPLE_M).toInt()).map { p.shape.pointAt(it * SAMPLE_M) }
        val new = samples.count { !coverage.covers(it) }
        (new > samples.size * (1 - COVERED_SHARE)).also { keep -> if (keep) samples.forEach(coverage::add) }
    }

private const val SAMPLE_M = 25.0
private const val COVERED_SHARE = 0.9

/** A coarse grid of where track already runs (cells of about 30 m, neighbours included). */
private class TrackCoverage {
    private val cells = HashSet<Long>()

    private fun cell(p: LatLon): Pair<Int, Int> =
        floor(p.lon * cos(Math.toRadians(p.lat)) * 111_320 / CELL_M).toInt() to floor(p.lat * 110_540 / CELL_M).toInt()

    fun add(p: LatLon) {
        val (x, y) = cell(p)
        cells += key(x, y)
    }

    fun covers(p: LatLon): Boolean {
        val (x, y) = cell(p)
        for (dx in -1..1) for (dy in -1..1) if (key(x + dx, y + dy) in cells) return true
        return false
    }

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)

    private companion object {
        const val CELL_M = 30.0
    }
}
