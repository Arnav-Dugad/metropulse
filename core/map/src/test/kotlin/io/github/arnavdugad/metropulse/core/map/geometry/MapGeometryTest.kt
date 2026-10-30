package io.github.arnavdugad.metropulse.core.map.geometry

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.geo.LineString
import io.github.arnavdugad.metropulse.core.domain.geo.haversineM
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.Track
import io.github.arnavdugad.metropulse.core.domain.transit.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeometryTest {

    /** 1 km due north from Bengaluru. */
    private val north = listOf(LatLon(12.97, 77.59), LatLon(12.97 + 1000 / 111_195.0, 77.59))

    @Test
    fun `a strip has the requested width and covers the line ends`() {
        val quad = MapGeometry.strip(north, 10.0).single()
        assertEquals(5, quad.size)
        assertEquals(quad.first(), quad.last())
        assertEquals(10.0, haversineM(quad[0], quad[3]), 0.05)
        assertEquals(1010.0, haversineM(quad[0], quad[1]), 0.5) // half a width past each end
    }

    @Test
    fun `offset strips sit beside the line`() {
        val left = MapGeometry.strip(north, 1.0, offsetM = 4.0).single()
        // Heading north, left is west: every corner is 3.5 to 4.5 m west of the line.
        val westM = (north[0].lon - left[0].lon) * 108_500.0
        assertEquals(4.5, westM, 0.05)
    }

    @Test
    fun `oriented rectangles follow the bearing`() {
        val rect = MapGeometry.orientedRect(north[0], 90.0, alongM = 10.0, acrossM = 2.0)
        assertEquals(5, rect.size)
        assertEquals(10.0, haversineM(rect[0], rect[1]), 0.05) // along: east-west
        assertEquals(2.0, haversineM(rect[1], rect[2]), 0.05)
    }

    @Test
    fun `train cars trail behind the nose and keep their size`() {
        val line = LineString(north)
        val cars = MapGeometry.trainCars(line, headAlongM = 500.0, cars = 4, carLengthM = 20.0, gapM = 1.0, widthM = 3.0)
        assertEquals(4, cars.size)
        val first = cars[0].ring
        // Sampled left side (7 points) + right side (7 points) + closing point.
        assertEquals(15, first.size)
        assertEquals(20.0, haversineM(first[0], first[6]), 0.1)
        assertEquals(3.0, haversineM(first[0], first[13]), 0.05) // full width at the back
        assertEquals(3.0 * 0.55, haversineM(first[6], first[7]), 0.05) // tapered nose
        assertEquals(490.0, cars[0].centerAlongM, 1e-9)
        // The nose is at 500 m; the last car's tail at 500 - 4*20 - 3*1.
        val tail = cars.last().ring[0]
        assertEquals(500.0 - 83.0, haversineM(north[0], tail), 0.5)
    }

    @Test
    fun `cars are cut off at the start of the line`() {
        val cars = MapGeometry.trainCars(LineString(north), headAlongM = 30.0, cars = 4, carLengthM = 20.0, gapM = 1.0, widthM = 3.0)
        assertEquals(2, cars.size)
    }

    @Test
    fun `track profile splits a pattern into elevated and underground stretches`() {
        val a = LatLon(12.97, 77.59)
        fun at(m: Double) = LatLon(a.lat + m / 111_195.0, a.lon)
        val shape = LineString(listOf(a, at(3000.0)))
        val pattern = Pattern(1, "L", 0, "", listOf("s0", "s1"), doubleArrayOf(0.0, 3000.0), shape)
        // OSM maps two parallel ways 5 m apart; a 40 m level-crossing slip in the middle of the viaduct.
        val east = 5 / 108_500.0
        val tracks = listOf(
            Track(TrackKind.Underground, listOf("L"), listOf(at(0.0), at(1000.0))),
            Track(TrackKind.Elevated, listOf("L"), listOf(at(1000.0).let { LatLon(it.lat, it.lon + east) }, at(1500.0))),
            Track(TrackKind.AtGrade, listOf("L"), listOf(at(1500.0), at(1540.0))),
            Track(TrackKind.Elevated, listOf("L"), listOf(at(1540.0), at(2400.0))),
            // Another line's track nearby is ignored.
            Track(TrackKind.Underground, listOf("Other"), listOf(at(2400.0), at(3000.0))),
        )
        val spans = TrackProfile(tracks).spans(pattern)
        assertEquals(listOf(TrackKind.Underground, TrackKind.Elevated), spans.map { it.kind })
        assertEquals(1000.0, spans[0].toM, 20.0)
        assertEquals(3000.0, spans.last().toM, 0.01)
    }

    @Test
    fun `without mapped track everything is at grade`() {
        val pattern = Pattern(1, "L", 0, "", listOf("a", "b"), doubleArrayOf(0.0, 1000.0), LineString(north))
        assertTrue(TrackProfile(emptyList()).spans(pattern).single().kind == TrackKind.AtGrade)
    }
}
