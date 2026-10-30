package io.github.arnavdugad.metropulse.core.domain.onboard

import io.github.arnavdugad.metropulse.core.domain.routing.JourneyPlanner
import io.github.arnavdugad.metropulse.core.domain.transit.MONDAY
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.TestNetworkBuilder
import io.github.arnavdugad.metropulse.core.domain.transit.hm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTrackerTest {

    // Line A: S0..S5 running north, 1 km apart, both directions; trains every 5 minutes.
    // Hops take 120 s with a 30 s dwell. Line B crosses at S3 (platform B0, same interchange).
    private val network = TestNetworkBuilder().apply {
        (0..5).forEach { station("S$it", 0.0, it.toDouble(), complex = if (it == 3) "X" else "S$it") }
        station("B0", 0.05, 3.0, complex = "X").station("B1", 1.0, 3.0).station("B2", 2.0, 3.0)
    }
        .route("A", (0..5).map { "S$it" }, hm(6, 0), hm(22, 0), 300)
        .route("A", (5 downTo 0).map { "S$it" }, hm(6, 0), hm(22, 0), 300, direction = 1)
        .route("B", listOf("B0", "B1", "B2"), hm(6, 2), hm(22, 0), 300)
        .transfer("S3", "B0", 90).transfer("B0", "S3", 90)
        .build()
    private val zone = network.timezone
    private val north: Pattern = network.patterns[0]
    private val south: Pattern = network.patterns[1]

    private fun at(secs: Int) = epochMillisOf(MONDAY, secs, zone)

    private fun fix(secs: Int, pattern: Pattern, alongM: Double) = GpsFix(at(secs), pattern.shape.pointAt(alongM), 8.0)

    private fun tracker(to: String, alertStops: Int = 1): TripTracker {
        val journey = JourneyPlanner(network).plan("S0", to, MONDAY, hm(7, 58), departures = 1).journeys.first()
        return TripTracker(network, journey, alertStops)
    }

    /** Where the 08:00 northbound train is, by the test timetable. */
    private fun scheduled(secsAfterDeparture: Int): Double {
        val d = north.stopDistM
        var t = secsAfterDeparture.toDouble()
        for (i in 0 until d.size - 1) {
            if (t < 120) return d[i] + (d[i + 1] - d[i]) * t / 120
            t -= 120
            if (t < 30) return d[i + 1]
            t -= 30
        }
        return d.last()
    }

    @Test
    fun `boards, alerts one stop before and arrives`() {
        val t = tracker("S4")
        assertEquals(TripPhase.WaitingToBoard, t.onFix(fix(hm(7, 59), north, 0.0)).phase)

        var alertAt = -1
        var p = t.progress
        for (s in 0..600 step 2) {
            p = t.onFix(fix(hm(8, 0) + s, north, scheduled(s)))
            if (p.alertDue && alertAt < 0) alertAt = s
            if (p.phase == TripPhase.Arrived) break
        }
        assertEquals(TripPhase.Arrived, p.phase)
        // The alert goes once the train leaves S3 (the stop before S4): 3 hops + 3 dwells = 450 s.
        assertTrue("alert at $alertAt", alertAt in 450..470)
        assertEquals(0, p.stopsToDestination)
    }

    @Test
    fun `riding live counts stops and time`() {
        val t = tracker("S4")
        t.onFix(fix(hm(7, 59), north, 0.0))
        var p = t.progress
        for (s in 0..200 step 2) p = t.onFix(fix(hm(8, 0) + s, north, scheduled(s)))
        // 200 s in: between S1 and S2, so S2, S3, S4 are left.
        assertEquals(TripPhase.Riding, p.phase)
        assertEquals(PositionSource.Live, p.source)
        assertEquals(3, p.stopsToAlight)
        assertEquals(570.0 - 200, p.secondsToDestination!!.toDouble(), 20.0)
        assertTrue(p.fraction in 0.3..0.45)
        assertFalse(p.alertDue)
    }

    @Test
    fun `in a tunnel the schedule carries the train on`() {
        val t = tracker("S5")
        t.onFix(fix(hm(7, 59), north, 0.0))
        for (s in 0..100 step 2) t.onFix(fix(hm(8, 0) + s, north, scheduled(s)))
        val p = t.onTick(at(hm(8, 0) + 280))
        assertEquals(PositionSource.Scheduled, p.source)
        assertEquals(scheduled(280), p.alongM, 60.0)
    }

    @Test
    fun `with no GPS at all the rider is assumed aboard the timetabled train`() {
        val t = tracker("S4")
        assertEquals(TripPhase.WaitingToBoard, t.onTick(at(hm(8, 0))).phase)
        val p = t.onTick(at(hm(8, 2)))
        assertEquals(TripPhase.Riding, p.phase)
        assertEquals(PositionSource.Scheduled, p.source)
        assertEquals(scheduled(120), p.alongM, 5.0)
    }

    @Test
    fun `a train going the other way is flagged`() {
        val t = tracker("S4")
        var p = t.progress
        for (s in 0..60 step 2) p = t.onFix(fix(hm(8, 0) + s, south, 2_000.0 + 12.0 * s))
        assertTrue(p.wrongDirection)
        assertEquals(TripPhase.WaitingToBoard, p.phase)
    }

    @Test
    fun `waiting on the platform is live`() {
        val p = tracker("S4").onFix(fix(hm(7, 59), north, 20.0))
        assertEquals(TripPhase.WaitingToBoard, p.phase)
        assertEquals(PositionSource.Live, p.source)
    }

    @Test
    fun `a parallel pattern in the same direction is not the wrong way`() {
        // A short-turn S0..S3 pattern leaving a minute earlier; the GPS trace follows the full line.
        val net = TestNetworkBuilder().apply { (0..5).forEach { station("S$it", 0.0, it.toDouble()) } }
            .route("A", (0..5).map { "S$it" }, hm(6, 0), hm(22, 0), 300)
            .route("A", listOf("S0", "S1", "S2", "S3"), hm(5, 59), hm(22, 0), 300)
            .route("A", (5 downTo 0).map { "S$it" }, hm(6, 0), hm(22, 0), 300, direction = 1)
            .build()
        net.patterns[1].direction.let { assertEquals(0, it) }
        val journey = JourneyPlanner(net).plan("S0", "S2", MONDAY, hm(7, 58), departures = 1).journeys.first()
        assertEquals(net.patterns[1].id, journey.rides.single().patternId)
        val t = TripTracker(net, journey)
        val full = net.patterns[0]
        var p = t.progress
        for (s in 0..120 step 2) p = t.onFix(GpsFix(at(hm(7, 59) + s), full.shape.pointAt(10.0 * s), 8.0))
        assertFalse(p.wrongDirection)
        assertEquals(TripPhase.Riding, p.phase)
    }

    @Test
    fun `changing trains moves on to the next ride`() {
        val t = tracker("B2")
        assertEquals(listOf("A", "B"), t.journey.lineSequence)
        val first = t.journey.rides[0]
        var p = t.progress
        val dep = first.departSecs!!
        for (s in 0..500 step 2) {
            p = t.onFix(fix(dep + s, north, scheduled(s)))
            if (p.rideIndex == 1) break
        }
        assertEquals(1, p.rideIndex)
        assertEquals(TripPhase.WaitingToBoard, p.phase)
        assertEquals(2, p.stopsToDestination)
    }
}
