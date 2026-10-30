package io.github.arnavdugad.metropulse.core.domain.routing

import io.github.arnavdugad.metropulse.core.domain.transit.MONDAY
import io.github.arnavdugad.metropulse.core.domain.transit.SUNDAY
import io.github.arnavdugad.metropulse.core.domain.transit.TestNetworkBuilder
import io.github.arnavdugad.metropulse.core.domain.transit.hm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaptorTest {

    // Line A: A1 - A2 - X - A3, every 5 min.  Line B: B1 - Xb - B2, every 30 min from 06:10.
    // X and Xb are platforms of one interchange, 3 min apart.  Line C: a slow direct A1 - Cm - B2.
    // Every hop takes 2 min plus a 30 s dwell (Line C: 15 min hops).
    private val network = TestNetworkBuilder()
        .station("A1", 0.0, 0.0).station("A2", 1.0, 0.0).station("X", 2.0, 0.0, complex = "X").station("A3", 3.0, 0.0)
        .station("B1", 2.0, -1.0).station("Xb", 2.0, 0.05, complex = "X").station("B2", 2.0, 1.0)
        .station("Cm", 1.0, 1.0)
        .route("A", listOf("A1", "A2", "X", "A3"), hm(6, 0), hm(22, 0), 300)
        .route("B", listOf("B1", "Xb", "B2"), hm(6, 10), hm(22, 10), 1800)
        .route("C", listOf("A1", "Cm", "B2"), hm(6, 0), hm(22, 0), 600, hopSecs = 900)
        .transfer("X", "Xb", 180).transfer("Xb", "X", 180)
        .build()

    private fun search(from: String, to: String, at: Int, date: java.time.LocalDate = MONDAY) =
        Raptor(RaptorTimetable(network, date)).search(listOf(from), listOf(to), at)

    @Test
    fun `direct ride takes the next train`() {
        val j = search("A1", "A3", hm(7, 1)).single()
        val ride = j.legs.single() as Leg.Ride
        assertEquals("A", ride.lineId)
        assertEquals(hm(7, 5), j.departSecs)
        assertEquals(hm(7, 5) + 3 * 120 + 2 * 30, j.arriveSecs)
        assertEquals(3, ride.stopCount)
        assertTrue(j.timed)
    }

    @Test
    fun `returns the pareto set of arrival time and changes`() {
        val journeys = search("A1", "B2", hm(7, 0))
        assertEquals(2, journeys.size)
        val direct = journeys[0]
        assertEquals(listOf("C"), direct.lineSequence)
        assertEquals(hm(7, 0) + 2 * 900 + 30, direct.arriveSecs)

        val withChange = journeys[1]
        assertEquals(listOf("A", "B"), withChange.lineSequence)
        assertEquals(1, withChange.transfers)
        // B leaves B1 at 07:10, so Xb at 07:12:30 and B2 at 07:14:30.
        assertEquals(hm(7, 14, 30), withChange.arriveSecs)
        val walk = withChange.legs[1] as Leg.Walk
        assertEquals("X" to "Xb", walk.fromStationId to walk.toStationId)
        assertEquals(180, walk.durationSecs)
    }

    @Test
    fun `leaves as late as the connection allows`() {
        val j = search("A1", "B2", hm(7, 0)).first { it.transfers == 1 }
        // The 07:00 train would wait at the interchange; the 07:05 one (X at 07:09:30) still makes
        // the 07:12:30 departure after the 3-minute walk.
        assertEquals(hm(7, 5), j.departSecs)
        val (a, b) = j.rides
        assertTrue(a.arriveSecs!! + 180 <= b.departSecs!!)
    }

    @Test
    fun `a published change shorter than two minutes is not trusted`() {
        // D reaches the shared stop S at 07:02; E leaves S at 07:03 and every 10 min after. The
        // feed claims a 60 s change, but planning allows two minutes, so the 07:13 is taken.
        val shared = TestNetworkBuilder()
            .station("D1", 0.0, 0.0).station("S", 1.0, 0.0).station("D2", 2.0, 0.0)
            .station("E1", 1.0, -1.0).station("E2", 1.0, 1.0)
            .route("D", listOf("D1", "S", "D2"), hm(7, 0), hm(7, 0), 600)
            .route("E", listOf("E1", "S", "E2"), hm(7, 0, 30), hm(8, 0, 30), 600)
            .transfer("S", "S", 60)
            .build()
        val j = Raptor(RaptorTimetable(shared, MONDAY)).search(listOf("D1"), listOf("E2"), hm(7, 0)).single()
        assertEquals(hm(7, 13), j.rides[1].departSecs)
    }

    @Test
    fun `after the last train the first train of the next day is found`() {
        val j = search("A1", "A3", hm(23, 30)).single()
        assertEquals(86_400 + hm(6, 0), j.departSecs)
    }

    @Test
    fun `trips only run on their service days`() {
        val weekdays = TestNetworkBuilder()
            .station("P", 0.0, 0.0).station("Q", 1.0, 0.0)
            .service("weekdays", 0b0011111)
            .route("W", listOf("P", "Q"), hm(6, 0), hm(22, 0), 600, serviceId = "weekdays")
            .build()
        val raptor = Raptor(RaptorTimetable(weekdays, SUNDAY))
        val j = raptor.search(listOf("P"), listOf("Q"), hm(10, 0)).single()
        assertEquals("Sunday has no service; Monday's first train", 86_400 + hm(6, 0), j.departSecs)
    }

    @Test
    fun `after-midnight trains of the previous service day are used`() {
        val late = TestNetworkBuilder()
            .station("P", 0.0, 0.0).station("Q", 1.0, 0.0)
            .route("N", listOf("P", "Q"), hm(24, 20), hm(24, 20), 600)
            .build()
        // 00:10 on Monday: Sunday's 24:20 trip leaves at 00:20 Monday.
        val j = Raptor(RaptorTimetable(late, MONDAY)).search(listOf("P"), listOf("Q"), hm(0, 10)).single()
        assertEquals(hm(0, 20), j.departSecs)
    }

    @Test
    fun `origin inside the destination complex gives nothing`() {
        assertTrue(search("X", "X", hm(8, 0)).isEmpty())
        assertFalse(search("A1", "X", hm(8, 0)).isEmpty())
    }
}
