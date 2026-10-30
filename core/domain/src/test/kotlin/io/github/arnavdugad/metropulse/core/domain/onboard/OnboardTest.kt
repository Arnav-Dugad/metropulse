package io.github.arnavdugad.metropulse.core.domain.onboard

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.transit.TestNetworkBuilder
import io.github.arnavdugad.metropulse.core.domain.transit.hm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OnboardTest {

    // Six stations 1 km apart running north, mapped in both directions (patterns 1 and 2).
    private val ids = (0..5).map { "S$it" }
    private val network = TestNetworkBuilder().apply { ids.forEachIndexed { i, id -> station(id, 0.0, i.toDouble()) } }
        .route("L", ids, hm(6, 0), hm(22, 0), 600)
        .route("L", ids.reversed(), hm(6, 0), hm(22, 0), 600)
        .build()
    private val north = network.patterns[0]
    private val south = network.patterns[1]

    /** A noisy fix [alongM] metres along the northbound track, pushed [eastM] sideways. */
    private fun fix(t: Long, alongM: Double, rnd: Random, eastM: Double = 0.0, accuracy: Double = 10.0): GpsFix {
        val p = north.shape.pointAt(alongM)
        val dx = eastM + rnd.nextDouble(-8.0, 8.0)
        val dy = rnd.nextDouble(-8.0, 8.0)
        return GpsFix(t, LatLon(p.lat + dy / 111_195.0, p.lon + dx / 108_500.0), accuracy)
    }

    @Test
    fun `tracks a train and its speed`() {
        val rnd = Random(42)
        val matcher = MapMatcher(network.patterns)
        var state: MatchState? = null
        for (i in 0..30) state = matcher.update(fix(i * 2_000L, 100 + 15.0 * i * 2, rnd))
        state!!
        assertEquals(north.id, state.patternId)
        assertEquals(1_000.0, state.alongM, 25.0)
        assertEquals(15.0, state.speedMps, 2.0)
        assertTrue(state.confidence > 0.5)
    }

    @Test
    fun `picks the direction of travel`() {
        val rnd = Random(7)
        val matcher = MapMatcher(network.patterns)
        var state: MatchState? = null
        for (i in 0..20) state = matcher.update(fix(i * 2_000L, 4_000 - 12.0 * i * 2, rnd))
        assertEquals(south.id, state!!.patternId)
        val expectedSouthAlong = north.shape.lengthM - (4_000 - 12.0 * 40)
        assertEquals(expectedSouthAlong, state.alongM, 30.0)
    }

    @Test
    fun `rejects a wild fix and coasts through a gap`() {
        val rnd = Random(3)
        val matcher = MapMatcher(network.patterns)
        for (i in 0..10) matcher.update(fix(i * 2_000L, 100 + 15.0 * i * 2, rnd))
        // A reflection 600 m off the line.
        val afterJump = matcher.update(fix(22_000, 430.0, rnd, eastM = 600.0))!!
        assertEquals(430.0, afterJump.alongM, 30.0)
        assertTrue(afterJump.offsetM < 30)

        // 40 s tunnel without fixes: the estimate keeps moving at the matched speed.
        val coasting = matcher.predict(62_000)!!
        assertEquals(430.0 + 15 * 40, coasting.alongM, 60.0)
        val resumed = matcher.update(fix(64_000, 100 + 15.0 * 64, rnd))!!
        assertEquals(1_060.0, resumed.alongM, 30.0)
    }

    @Test
    fun `dead reckoning follows the schedule and dwells at stations`() {
        val profile = network.profiles.first { it.patternId == north.id }
        val dr = DeadReckoner(north, profile)
        val d = north.stopDistM
        // Hops take 120 s and trains dwell 30 s (see TestNetworkBuilder).
        assertEquals(d[1], dr.advance(d[0], 120.0), 1.0)
        assertEquals("still dwelling", d[1], dr.advance(d[0], 140.0), 1.0)
        assertEquals((d[1] + d[2]) / 2, dr.advance(d[0], 150.0 + 60), 1.0)
        assertEquals(d.last(), dr.advance(d[0], 10_000.0), 1.0)
        assertEquals(2, dr.nextStopIndex((d[1] + d[2]) / 2))
        assertEquals(60.0, dr.secondsToStop((d[1] + d[2]) / 2, 2), 1.0)
    }

    @Test
    fun `dead reckoning without run times estimates from distance`() {
        val dr = DeadReckoner(north, null)
        // ~1 km at 33 km/h is about 109 s.
        assertEquals(north.stopDistM[1], dr.advance(0.0, 109.0), 15.0)
    }
}
