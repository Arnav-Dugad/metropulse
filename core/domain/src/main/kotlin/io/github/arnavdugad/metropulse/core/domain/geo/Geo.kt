package io.github.arnavdugad.metropulse.core.domain.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6_371_000.0
private const val DEG = PI / 180.0

fun haversineM(a: LatLon, b: LatLon): Double {
    val dLat = (b.lat - a.lat) * DEG
    val dLon = (b.lon - a.lon) * DEG
    val h = sin(dLat / 2).let { it * it } + cos(a.lat * DEG) * cos(b.lat * DEG) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

/** Google encoded polyline with precision 6, as written by tools/metropipe/geo.py. */
object Polyline {
    fun decode(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (axis in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (axis == 0) lat += delta else lon += delta
            }
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }
}

/** Where a point falls on a [LineString]. */
data class Projection(
    /** Metres along the line from its start. */
    val alongM: Double,
    /** Perpendicular distance from the line, in metres. */
    val offsetM: Double,
    val segment: Int,
)

/**
 * A polyline with cumulative distances, for linear referencing (position along a line).
 * Projection uses a local equirectangular plane, accurate to centimetres over a city.
 */
class LineString(val points: List<LatLon>) {
    init {
        require(points.isNotEmpty()) { "empty line" }
    }

    private val cosLat = cos(points.sumOf { it.lat } / points.size * DEG)
    private val xs = DoubleArray(points.size) { points[it].lon * DEG * EARTH_RADIUS_M * cosLat }
    private val ys = DoubleArray(points.size) { points[it].lat * DEG * EARTH_RADIUS_M }

    /** Distance from the start to each vertex. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { c ->
        for (i in 1 until points.size) c[i] = c[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }

    val lengthM: Double get() = cumulative.last()

    /** Nearest point on the line. With [fromM]/[toM], only that stretch is considered. */
    fun project(p: LatLon, fromM: Double = 0.0, toM: Double = lengthM): Projection {
        if (points.size == 1) return Projection(0.0, haversineM(p, points[0]), 0)
        val px = p.lon * DEG * EARTH_RADIUS_M * cosLat
        val py = p.lat * DEG * EARTH_RADIUS_M
        var best = Projection(0.0, Double.MAX_VALUE, 0)
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < fromM || cumulative[i] > toM) continue
            val dx = xs[i + 1] - xs[i]
            val dy = ys[i + 1] - ys[i]
            val len2 = dx * dx + dy * dy
            val seg = cumulative[i + 1] - cumulative[i]
            // Only the part of this segment inside [fromM, toM] may be matched.
            val tMin = if (seg == 0.0) 0.0 else ((fromM - cumulative[i]) / seg).coerceIn(0.0, 1.0)
            val tMax = if (seg == 0.0) 1.0 else ((toM - cumulative[i]) / seg).coerceIn(0.0, 1.0)
            val t = if (len2 == 0.0) 0.0 else (((px - xs[i]) * dx + (py - ys[i]) * dy) / len2).coerceIn(tMin, tMax)
            val d = hypot(px - (xs[i] + t * dx), py - (ys[i] + t * dy))
            if (d < best.offsetM) best = Projection(cumulative[i] + t * seg, d, i)
        }
        return best
    }

    /** The point [alongM] metres from the start (clamped to the line). */
    fun pointAt(alongM: Double): LatLon {
        val d = alongM.coerceIn(0.0, lengthM)
        var i = cumulative.binarySearch(d).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 1)
        if (i == points.size - 1) return points.last()
        val seg = cumulative[i + 1] - cumulative[i]
        val t = if (seg == 0.0) 0.0 else (d - cumulative[i]) / seg
        return LatLon(points[i].lat + t * (points[i + 1].lat - points[i].lat), points[i].lon + t * (points[i + 1].lon - points[i].lon))
    }

    /** Direction of travel at [alongM], in degrees clockwise from north. */
    fun bearingAt(alongM: Double): Double {
        if (points.size < 2) return 0.0
        val d = alongM.coerceIn(0.0, lengthM)
        val i = cumulative.binarySearch(d).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        val deg = Math.toDegrees(kotlin.math.atan2(xs[i + 1] - xs[i], ys[i + 1] - ys[i]))
        return (deg + 360) % 360
    }

    private fun hypot(x: Double, y: Double) = sqrt(x * x + y * y)
}
