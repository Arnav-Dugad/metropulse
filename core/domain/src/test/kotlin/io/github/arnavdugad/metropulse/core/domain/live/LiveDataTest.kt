package io.github.arnavdugad.metropulse.core.domain.live

import io.github.arnavdugad.metropulse.core.domain.transit.Departures
import io.github.arnavdugad.metropulse.core.domain.transit.MONDAY
import io.github.arnavdugad.metropulse.core.domain.transit.TestNetworkBuilder
import io.github.arnavdugad.metropulse.core.domain.transit.hm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDataTest {

    // Line A: P - Q - R and back, every 10 min; hops take 2 min.
    private val network = TestNetworkBuilder()
        .station("P", 0.0, 0.0).station("Q", 1.0, 0.0).station("R", 2.0, 0.0)
        .route("A", listOf("P", "Q", "R"), hm(6, 0), hm(22, 0), 600)
        .route("A", listOf("R", "Q", "P"), hm(6, 5), hm(22, 5), 600, direction = 1)
        .build()
    private val midnight = MONDAY.atStartOfDay(network.timezone).toEpochSecond()
    private val now = midnight + hm(8, 0)

    @Test
    fun `predictions replace the timetable in the group they belong to`() {
        val groups = Departures.at(network, "Q", MONDAY, hm(8, 0))
        val live = LiveSnapshot(
            listOf(
                LiveArrival("A", "Q", now + 90, vehicle = "t1", nextStationId = "R"), // towards R, by next stop
                LiveArrival("A", "Q", now + 400, vehicle = "t2", destinationStationId = "R"), // towards R, by destination
            ),
            fetchedAtEpochSec = now,
        )
        val merged = LiveDepartures.merge(network, "Q", MONDAY, groups, live)
        val toR = merged.single { it.group.headsign == "R" }
        assertTrue(toR.live)
        assertEquals(listOf(hm(8, 1, 30), hm(8, 6, 40)), toR.group.next.map { it.secs })
        val toP = merged.single { it.group.headsign == "P" }
        assertFalse(toP.live) // no predictions: timetable times stay
        assertEquals(groups.single { it.headsign == "P" }.next, toP.group.next)
    }

    @Test
    fun `a train is placed between stops by its time to the next stop`() {
        // Due at R in 60 s; the Q-R hop takes 120 s, so it is halfway from Q.
        val live = LiveSnapshot(listOf(LiveArrival("A", "R", now + 60, vehicle = "t1", destinationStationId = "R")), now)
        val train = LivePositions.estimate(network, live, now, setOf("A")).single()
        val pattern = network.patternById.getValue(train.patternId)
        assertEquals(listOf("P", "Q", "R"), pattern.stationIds)
        val (q, r) = pattern.stopDistM[1] to pattern.stopDistM[2]
        assertEquals(q + (r - q) / 2, train.alongM, 1.0)
    }

    @Test
    fun `predictions without a vehicle are not drawn`() {
        val live = LiveSnapshot(listOf(LiveArrival("A", "R", now + 60, destinationStationId = "R")), now)
        assertTrue(LivePositions.estimate(network, live, now, setOf("A")).isEmpty())
    }
}
