package io.github.arnavdugad.metropulse.core.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun `decodes polylines written by the data pipeline`() {
        // tools/metropipe/geo.py encode_polyline([(12.971599, 77.594566), (12.978, 77.6012), (-1.5, -0.25)])
        val points = Polyline.decode("}cvvWk{~~rCaoKs}K~itrZ~btnsC")
        assertEquals(3, points.size)
        assertEquals(12.971599, points[0].lat, 1e-9)
        assertEquals(77.594566, points[0].lon, 1e-9)
        assertEquals(77.6012, points[1].lon, 1e-9)
        assertEquals(-1.5, points[2].lat, 1e-9)
        assertEquals(-0.25, points[2].lon, 1e-9)
    }

    @Test
    fun `haversine distance`() {
        // One degree of latitude is about 111.2 km.
        assertEquals(111_195.0, haversineM(LatLon(12.0, 77.0), LatLon(13.0, 77.0)), 50.0)
    }

    @Test
    fun `projects onto an L-shaped line`() {
        val line = LineString(listOf(LatLon(12.9, 77.6), LatLon(12.91, 77.6), LatLon(12.91, 77.61)))
        val leg1 = haversineM(line.points[0], line.points[1])
        assertEquals(leg1 + haversineM(line.points[1], line.points[2]), line.lengthM, 1.0)

        // 100 m east of the middle of the first leg.
        val p = line.project(LatLon(12.905, 77.6 + 100 / 108_500.0))
        assertEquals(leg1 / 2, p.alongM, 2.0)
        assertEquals(100.0, p.offsetM, 2.0)
        assertEquals(0, p.segment)

        val back = line.pointAt(p.alongM)
        assertEquals(12.905, back.lat, 1e-5)
        assertEquals(0.0, line.bearingAt(10.0), 0.5)
        assertEquals(90.0, line.bearingAt(leg1 + 10), 0.5)
    }

    @Test
    fun `projection can be limited to a stretch of the line`() {
        // An out-and-back line: the same point lies on both halves.
        val line = LineString(listOf(LatLon(12.9, 77.6), LatLon(12.91, 77.6), LatLon(12.9, 77.6)))
        val half = line.lengthM / 2
        val p = line.project(LatLon(12.902, 77.6), fromM = half, toM = line.lengthM)
        assertEquals(true, p.alongM > half)
    }
}
