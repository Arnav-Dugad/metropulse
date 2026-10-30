package io.github.arnavdugad.metropulse.core.domain.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeparturesTest {

    // A: P - Q - R both ways every 10 minutes 06:00-22:00 (hops 2 min, 30 s dwell).
    // U: an untimed line through Q (platform Qu of the same interchange).
    private val network = TestNetworkBuilder()
        .station("P", 0.0, 0.0).station("Q", 1.0, 0.0, complex = "Q").station("R", 2.0, 0.0)
        .station("Qu", 1.0, 0.05, complex = "Q").station("V", 1.0, 1.0)
        .route("A", listOf("P", "Q", "R"), hm(6, 0), hm(22, 0), 600)
        .route("A", listOf("R", "Q", "P"), hm(6, 5), hm(22, 5), 600, direction = 1)
        .untimedRoute("U", listOf("Qu", "V"))
        .build()

    @Test
    fun `next departures per direction with first and last`() {
        val groups = Departures.at(network, "Q", MONDAY, hm(8, 0), count = 2)
        val toR = groups.first { it.headsign == "R" }
        // Departs Q 150 s after leaving P (120 s hop + 30 s dwell).
        assertEquals(listOf(hm(8, 2, 30), hm(8, 12, 30)), toR.next.map { it.secs })
        assertEquals(hm(6, 2, 30), toR.firstSecs)
        assertEquals(hm(22, 2, 30), toR.lastSecs)
        assertTrue(toR.timed)
        val toP = groups.first { it.headsign == "P" }
        assertEquals(hm(8, 7, 30), toP.next.first().secs)
    }

    @Test
    fun `untimed lines are listed without times`() {
        val u = Departures.at(network, "Q", MONDAY, hm(8, 0)).single { it.line.id == "U" }
        assertFalse(u.timed)
        assertTrue(u.next.isEmpty())
        assertEquals("Qu", u.stationId)
    }

    @Test
    fun `no departures from a terminus in that direction`() {
        val groups = Departures.at(network, "R", MONDAY, hm(8, 0))
        assertEquals(listOf("P"), groups.map { it.headsign })
    }

    @Test
    fun `short trips join the main direction with their own headsign`() {
        // Extra P - Q shuttles at 08:05 and 08:15 run the same way as A towards R.
        val shuttles = TestNetworkBuilder()
            .station("P", 0.0, 0.0).station("Q", 1.0, 0.0).station("R", 2.0, 0.0)
            .route("A", listOf("P", "Q", "R"), hm(6, 0), hm(22, 0), 600)
            .route("A", listOf("P", "Q"), hm(8, 5), hm(8, 15), 600)
            .build()
        val group = Departures.at(shuttles, "P", MONDAY, hm(8, 0), count = 4).single()
        assertEquals("R", group.headsign)
        assertEquals(listOf("R", "Q", "R", "Q"), group.next.map { it.headsign })
    }

    @Test
    fun `friday is a weekday where the city has no friday timetable`() {
        val timedOnly = TestNetworkBuilder()
            .station("P", 0.0, 0.0).station("Q", 1.0, 0.0).station("R", 2.0, 0.0)
            .route("A", listOf("P", "Q", "R"), hm(6, 0), hm(22, 0), 600)
            .build()
        val friday = MONDAY.plusDays(4)
        assertEquals(DayType.Weekday, timedOnly.dayType(friday))
        assertEquals(DayType.Saturday, timedOnly.dayType(friday.plusDays(1)))
        assertEquals(hm(8, 2, 30), Departures.at(timedOnly, "Q", friday, hm(8, 0)).single().next.first().secs)
    }

    @Test
    fun `after the last train the list is empty but hours remain`() {
        val toR = Departures.at(network, "Q", MONDAY, hm(23, 30)).first { it.headsign == "R" }
        assertTrue(toR.next.isEmpty())
        assertEquals(hm(22, 2, 30), toR.lastSecs)
    }
}
