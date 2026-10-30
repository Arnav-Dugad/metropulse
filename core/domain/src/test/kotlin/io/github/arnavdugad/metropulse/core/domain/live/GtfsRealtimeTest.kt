package io.github.arnavdugad.metropulse.core.domain.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class GtfsRealtimeTest {

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
        fun fixed32(field: Int) = apply { raw(((field shl 3) or 5).toLong()); out.write(ByteArray(4)) }
        fun build(): ByteArray = out.toByteArray()
    }

    private fun trip(id: String, route: String) = Pb().string(1, id).string(5, route)
    private fun stop(id: String, arrival: Long) = Pb().message(2, Pb().varint(2, arrival)).string(4, id)

    @Test
    fun `trip updates and vehicle positions are joined by trip`() {
        val feed = Pb()
            .message(1, Pb().string(1, "2.0").varint(3, 1_800_000_000))
            .message(2, Pb().string(1, "e1").message(3, Pb()
                .message(1, trip("T1", "A"))
                .message(2, stop("A02N", 1_800_000_060))
                .message(2, stop("A03N", 1_800_000_150))))
            .message(2, Pb().string(1, "e2").message(4, Pb()
                .message(1, trip("T1", "A"))
                .fixed32(2) // a position we ignore
                .varint(4, 1) // STOPPED_AT
                .string(7, "A01N")))
            .build()
        val rt = GtfsRealtime.parse(feed)
        assertEquals(1_800_000_000L, rt.timestamp)
        val t = rt.trips.single()
        assertEquals("A", t.routeId)
        assertEquals(listOf("A02N", "A03N"), t.stops.map { it.stopId })
        assertEquals(1_800_000_150L, t.stops[1].time)
        assertEquals(RtVehicle("A01N", RtStatus.StoppedAt), t.vehicle)
    }

    @Test
    fun `a stop without times has none`() {
        val feed = Pb().message(2, Pb().message(3, Pb().message(1, trip("T2", "6")).message(2, Pb().string(4, "601S")))).build()
        assertNull(GtfsRealtime.parse(feed).trips.single().stops.single().time)
    }
}
