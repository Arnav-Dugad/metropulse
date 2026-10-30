package io.github.arnavdugad.metropulse.core.domain.live

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class VehiclePositionsTest {

    /** A tiny protobuf writer, enough to build test feeds. */
    private class Pb {
        private val out = ByteArrayOutputStream()
        private fun raw(v: Long) {
            var x = v
            while (true) {
                if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }
                out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
            }
        }
        fun varint(field: Int, v: Long) = apply { raw((field shl 3).toLong()); raw(v) }
        fun bytes(field: Int, b: ByteArray) = apply { raw(((field shl 3) or 2).toLong()); raw(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun message(field: Int, m: Pb) = bytes(field, m.build())
        fun float(field: Int, f: Float) = apply {
            raw(((field shl 3) or 5).toLong())
            val bits = f.toRawBits()
            for (i in 0 until 4) out.write((bits ushr (8 * i)) and 0xFF)
        }
        fun build(): ByteArray = out.toByteArray()
    }

    /** A FeedEntity carrying a VehiclePosition, shaped like Delhi's feed. */
    private fun bus(id: String, route: String, lat: Float, lon: Float, ts: Long) = Pb().string(1, id).message(
        4,
        Pb().message(1, Pb().string(1, "${route}_08_00").string(5, route))
            .message(2, Pb().float(1, lat).float(2, lon).float(5, 0f))
            .varint(5, ts)
            .message(8, Pb().string(1, id).string(2, id)),
    )

    private fun feed(vararg entities: Pb) =
        entities.fold(Pb().message(1, Pb().string(1, "2.0").varint(3, 1_000))) { f, e -> f.message(2, e) }.build()

    @Test
    fun `vehicle positions are decoded with route and time`() {
        val positions = VehiclePositions.parse(feed(bus("DL51EV1201", "1750", 28.67103f, 77.1589f, 990)))
        val p = positions.single()
        assertEquals("DL51EV1201", p.vehicleId)
        assertEquals("1750", p.routeId)
        assertEquals(28.67103, p.location.lat, 1e-5)
        assertEquals(77.1589, p.location.lon, 1e-5)
        assertEquals(990L, p.timestamp)
    }

    @Test
    fun `vehicles without a position are dropped`() {
        val noFix = Pb().string(1, "x").message(4, Pb().message(8, Pb().string(1, "x")))
        assertEquals(0, VehiclePositions.parse(feed(noFix, bus("y", "1", 0f, 0f, 990))).size)
    }

    @Test
    fun `nearby buses are filtered by distance and age, nearest first, with direction`() {
        val station = LatLon(28.6328, 77.2197)
        val positions = VehiclePositions.parse(
            feed(
                bus("near", "10", 28.6340f, 77.2197f, 990), // ~130 m north
                bus("far", "11", 28.6600f, 77.2197f, 990), // ~3 km
                bus("stale", "12", 28.6330f, 77.2197f, 100), // 15 min old
                bus("closer", "13", 28.6331f, 77.2197f, 995), // ~35 m
            ),
        )
        val routes = mapOf("10" to BusRoute("534", "Anand Vihar - Mehrauli"))
        val previous = mapOf("near" to LatLon(28.6360, 77.2197), "closer" to LatLon(28.6331, 77.2197))
        val buses = NearbyBuses.near(positions, listOf(station), routes, nowEpochSec = 1_000, previous = previous)
        assertEquals(listOf("closer", "near"), buses.map { it.vehicleId })
        assertEquals("534", buses[1].route?.name)
        assertEquals(BusMotion.Approaching, buses[1].motion)
        assertNull(buses[0].motion) // did not move
        assertEquals(5, buses[0].ageSecs)
    }
}
