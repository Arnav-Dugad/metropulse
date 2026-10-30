package io.github.arnavdugad.metropulse.core.map.geometry

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_000.0
private const val DEG = PI / 180.0

/** A flat metric plane around [origin]; accurate to centimetres across a city. */
class LocalPlane(origin: LatLon) {
    private val lat0 = origin.lat
    private val lon0 = origin.lon
    private val kx = EARTH_RADIUS_M * DEG * cos(origin.lat * DEG)
    private val ky = EARTH_RADIUS_M * DEG

    fun x(p: LatLon) = (p.lon - lon0) * kx
    fun y(p: LatLon) = (p.lat - lat0) * ky
    fun toLatLon(x: Double, y: Double) = LatLon(lat0 + y / ky, lon0 + x / kx)
}

/** A closed ring (first point repeated last), ready to become a GeoJSON polygon. */
typealias Ring = List<LatLon>

object MapGeometry {

    /**
     * A strip [widthM] wide along [line], shifted [offsetM] to the left of it (negative: right),
     * as one quad per segment. Each quad reaches half a width past its ends so consecutive quads
     * overlap at bends instead of leaving notches; with [extendEnds] false the strip's own two ends
     * stay flush (for pieces that butt against a neighbour at another height, like ramp steps).
     */
    fun strip(line: List<LatLon>, widthM: Double, offsetM: Double = 0.0, extendEnds: Boolean = true): List<Ring> {
        if (line.size < 2) return emptyList()
        val plane = LocalPlane(line[0])
        val half = widthM / 2
        val out = ArrayList<Ring>(line.size - 1)
        for (i in 0 until line.size - 1) {
            val ax = plane.x(line[i]); val ay = plane.y(line[i])
            val bx = plane.x(line[i + 1]); val by = plane.y(line[i + 1])
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            if (len < 0.01) continue
            val ux = (bx - ax) / len; val uy = (by - ay) / len // along
            val nx = -uy; val ny = ux // left normal
            val ext = minOf(half, len / 2)
            val startExt = if (i == 0 && !extendEnds) 0.0 else ext
            val endExt = if (i == line.size - 2 && !extendEnds) 0.0 else ext
            val sx = ax - ux * startExt + nx * offsetM; val sy = ay - uy * startExt + ny * offsetM
            val ex = bx + ux * endExt + nx * offsetM; val ey = by + uy * endExt + ny * offsetM
            out += listOf(
                plane.toLatLon(sx + nx * half, sy + ny * half),
                plane.toLatLon(ex + nx * half, ey + ny * half),
                plane.toLatLon(ex - nx * half, ey - ny * half),
                plane.toLatLon(sx - nx * half, sy - ny * half),
                plane.toLatLon(sx + nx * half, sy + ny * half),
            )
        }
        return out
    }

    /** A rectangle [alongM] long in the direction [bearingDeg] (clockwise from north) and [acrossM] wide, centred on [center]. */
    fun orientedRect(center: LatLon, bearingDeg: Double, alongM: Double, acrossM: Double): Ring {
        val plane = LocalPlane(center)
        val b = bearingDeg * DEG
        val ux = sin(b); val uy = cos(b) // along
        val nx = -uy; val ny = ux // left
        val ha = alongM / 2
        val hw = acrossM / 2
        val corners = listOf(-ha to hw, ha to hw, ha to -hw, -ha to -hw, -ha to hw)
        return corners.map { (a, w) -> plane.toLatLon(ux * a + nx * w, uy * a + ny * w) }
    }

    /** The part of [shape] between [fromM] and [toM], including the vertices in between. */
    fun slice(shape: LineString, fromM: Double, toM: Double): List<LatLon> {
        val a = fromM.coerceIn(0.0, shape.lengthM)
        val b = toM.coerceIn(0.0, shape.lengthM)
        if (b <= a) return emptyList()
        val out = ArrayList<LatLon>()
        out += shape.pointAt(a)
        for (i in shape.points.indices) {
            val c = shape.cumulative[i]
            if (c > a && c < b) out += shape.points[i]
        }
        out += shape.pointAt(b)
        return out
    }

    /** One car of a train: its footprint and where its middle is along the line. */
    class Car(val ring: Ring, val centerAlongM: Double, val index: Int)

    /**
     * Footprints of a train's cars, nose at [headAlongM] and the rest trailing back along [shape],
     * so the train bends through curves. The leading and trailing ends are tapered into a nose.
     * [widthM] is the full body width; [widthScale] narrows every car (for roof and bogie layers).
     */
    fun trainCars(
        shape: LineString, headAlongM: Double, cars: Int, carLengthM: Double, gapM: Double, widthM: Double,
        widthScale: Double = 1.0, taper: Boolean = true,
    ): List<Car> {
        val plane = LocalPlane(shape.points[0])
        val out = ArrayList<Car>(cars)
        val samples = 6
        for (c in 0 until cars) {
            val front = headAlongM - c * (carLengthM + gapM)
            val back = front - carLengthM
            if (front <= 0.0) break
            val left = ArrayList<LatLon>(samples + 1)
            val right = ArrayList<LatLon>(samples + 1)
            for (s in 0..samples) {
                val d = (back + (front - back) * s / samples).coerceIn(0.0, shape.lengthM)
                // Noses: the first car's front and the last car's back narrow over the last sample.
                val nose = taper && ((c == 0 && s == samples) || (c == cars - 1 && s == 0))
                val half = widthM * widthScale / 2 * (if (nose) NOSE_WIDTH else 1.0)
                val p = shape.pointAt(d)
                val bearing = shape.bearingAt(d) * DEG
                // Bearing is clockwise from north: direction (sin, cos); left normal (-cos, sin).
                val nx = -cos(bearing)
                val ny = sin(bearing)
                val x = plane.x(p); val y = plane.y(p)
                left += plane.toLatLon(x + nx * half, y + ny * half)
                right += plane.toLatLon(x - nx * half, y - ny * half)
            }
            out += Car(left + right.asReversed() + left.first(), (front + back) / 2, c)
        }
        return out
    }

    private const val NOSE_WIDTH = 0.55
}
