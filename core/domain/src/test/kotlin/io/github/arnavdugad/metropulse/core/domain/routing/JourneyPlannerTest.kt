package io.github.arnavdugad.metropulse.core.domain.routing

import io.github.arnavdugad.metropulse.core.domain.transit.MONDAY
import io.github.arnavdugad.metropulse.core.domain.transit.TestNetworkBuilder
import io.github.arnavdugad.metropulse.core.domain.transit.hm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPlannerTest {

    private val network = TestNetworkBuilder()
        .station("A1", 0.0, 0.0).station("A2", 1.0, 0.0).station("A3", 2.0, 0.0, complex = "J")
        .station("U0", 2.0, 0.02, complex = "J").station("U1", 2.0, 1.0).station("U2", 2.0, 2.0)
        .route("A", listOf("A1", "A2", "A3"), hm(6, 0), hm(22, 0), 300)
        .untimedRoute("U", listOf("U0", "U1", "U2"))
        .transfer("A3", "U0", 150).transfer("U0", "A3", 150)
        .build()

    private val planner = JourneyPlanner(network)

    @Test
    fun `timed journeys include later departures`() {
        val result = planner.plan("A1", "J", MONDAY, hm(8, 0), departures = 3)
        val departs = result.journeys.filter { it.timed }.map { it.departSecs }
        assertEquals(listOf(hm(8, 0), hm(8, 5), hm(8, 10)), departs)
    }

    @Test
    fun `a line without a timetable gives an untimed journey with an estimated duration`() {
        val result = planner.plan("A1", "U2", MONDAY, hm(8, 0))
        assertEquals(setOf("U"), result.untimedLines)
        val j = result.journeys.single()
        assertFalse(j.timed)
        assertNull(j.departSecs)
        assertEquals(listOf("A", "U"), j.lineSequence)
        // A: two hops from its profile (120 s + 30 s dwell + 120 s); walk 150 s; U: ~2 km at 33 km/h + one dwell.
        val u = j.rides[1]
        assertTrue(u.durationSecs in 200..260)
        assertEquals(270 + 150 + u.durationSecs, j.durationSecs)
    }

    @Test
    fun `same station gives no journeys`() {
        assertTrue(planner.plan("J", "J", MONDAY, hm(8, 0)).journeys.isEmpty())
    }
}
