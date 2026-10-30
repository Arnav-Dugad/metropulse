package io.github.arnavdugad.metropulse.core.domain.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTrainsTest {

    // P - Q - R, 2-minute hops and a 30 s dwell at Q; trains every 10 minutes 06:00-22:00.
    private val network = TestNetworkBuilder()
        .station("P", 0.0, 0.0).station("Q", 1.0, 0.0).station("R", 2.0, 0.0)
        .route("A", listOf("P", "Q", "R"), hm(6, 0), hm(22, 0), 600)
        .route("N", listOf("R", "Q"), hm(23, 59), hm(23, 59), 600)
        .build()
    private val trains = ScheduledTrains(network, MONDAY)
    private val d = network.patterns[0].stopDistM

    @Test
    fun `trains move between stops and dwell at them`() {
        val halfway = trains.positionsAt(hm(6, 1).toDouble()).single { it.lineId == "A" }
        assertEquals((d[0] + d[1]) / 2, halfway.alongM, 1.0)
        assertFalse(halfway.atStation)

        val dwelling = trains.positionsAt(hm(6, 2, 15).toDouble()).single { it.lineId == "A" }
        assertEquals(d[1], dwelling.alongM, 0.01)
        assertTrue(dwelling.atStation)
    }

    @Test
    fun `several trains run at once and none before the first departure`() {
        assertTrue(trains.positionsAt(hm(5, 0).toDouble()).none { it.lineId == "A" })
        // A trip lasts 4.5 minutes, so at most one train of A is out at a time with a 10-minute headway.
        assertEquals(1, trains.positionsAt(hm(9, 3).toDouble()).count { it.lineId == "A" })
    }

    @Test
    fun `yesterday's after-midnight train is still running`() {
        // Sunday's 23:59 trip on N is between R and Q at 00:00:30 Monday.
        val n = trains.positionsAt(30.0).single { it.lineId == "N" }
        assertTrue(n.alongM > 0)
    }
}
