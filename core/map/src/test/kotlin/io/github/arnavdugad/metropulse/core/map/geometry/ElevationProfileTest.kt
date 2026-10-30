package io.github.arnavdugad.metropulse.core.map.geometry

import io.github.arnavdugad.metropulse.core.domain.transit.TrackKind
import io.github.arnavdugad.metropulse.core.map.geometry.ElevationProfile.Companion.DECK_TOP
import io.github.arnavdugad.metropulse.core.map.geometry.ElevationProfile.Companion.RAMP_M
import io.github.arnavdugad.metropulse.core.map.geometry.ElevationProfile.Companion.TRENCH_M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProfileTest {

    private fun profile(vararg spans: Pair<Double, TrackKind>): ElevationProfile {
        var from = 0.0
        val list = spans.map { (len, kind) -> KindSpan(from, from + len, kind).also { from += len } }
        return ElevationProfile(list, from)
    }

    @Test
    fun `viaduct ramps down smoothly to at-grade track`() {
        val p = profile(2000.0 to TrackKind.Elevated, 2000.0 to TrackKind.AtGrade)
        assertEquals(DECK_TOP, p.top(1000.0), 1e-9)
        assertEquals(DECK_TOP / 2, p.top(2000.0), 1e-6) // the ramp is centred on the boundary
        assertEquals(0.0, p.top(2000.0 + RAMP_M / 2), 1e-9)
        // Monotonic, never a jump of more than a step's worth.
        var prev = p.top(1500.0)
        var s = 1500.0
        while (s < 2400.0) {
            s += 5.0
            val h = p.top(s)
            assertTrue(h <= prev + 1e-9)
            assertTrue(prev - h < 0.25)
            prev = h
        }
    }

    @Test
    fun `viaduct into a tunnel descends, then runs through a cutting to a portal`() {
        val p = profile(3000.0 to TrackKind.Elevated, 2000.0 to TrackKind.Underground)
        assertEquals(DECK_TOP, p.top(2000.0), 1e-9)
        assertEquals(0.0, p.top(3000.0 - TRENCH_M + 1), 1e-9) // on the ground before the cutting
        assertTrue(p.top(3000.0 - TRENCH_M - RAMP_M / 2) in 1.0..DECK_TOP - 1)
        assertEquals(listOf(ElevationProfile.Portal(3000.0, tunnelAhead = true)), p.portals)
        val trench = p.trenches.single()
        assertEquals(3000.0 - TRENCH_M, trench.fromM, 1e-9)
        assertTrue(trench.deepEndAtTo)
        assertTrue(p.inTunnel(3500.0))
        assertFalse(p.inTunnel(2900.0))
    }

    @Test
    fun `coming out of a tunnel the cutting is on the far side`() {
        val p = profile(1000.0 to TrackKind.Underground, 2000.0 to TrackKind.AtGrade)
        assertEquals(ElevationProfile.Portal(1000.0, tunnelAhead = false), p.portals.single())
        val t = p.trenches.single()
        assertEquals(1000.0, t.fromM, 1e-9)
        assertEquals(1000.0 + TRENCH_M, t.toM, 1e-9)
        assertFalse(t.deepEndAtTo)
    }

    @Test
    fun `short viaducts get shorter ramps`() {
        val p = profile(1000.0 to TrackKind.AtGrade, 300.0 to TrackKind.Elevated, 1000.0 to TrackKind.AtGrade)
        val peak = p.top(1150.0)
        assertTrue("peak $peak", peak > 0 && peak <= DECK_TOP)
        assertEquals(0.0, p.top(500.0), 1e-9)
    }

    @Test
    fun `raised pieces are long on the level and short on ramps`() {
        val p = profile(3000.0 to TrackKind.Elevated, 1000.0 to TrackKind.AtGrade)
        val pieces = p.raisedPieces()
        val level = pieces.filter { it.top == DECK_TOP }
        assertEquals(1, level.size)
        assertTrue(level.single().toM - level.single().fromM > 2000)
        val ramp = pieces.filter { it.top < DECK_TOP }
        assertTrue(ramp.all { it.toM - it.fromM <= ElevationProfile.STEP_M + 1e-9 })
        assertEquals(0.0, pieces.first().fromM, 1e-9) // a viaduct terminus starts at full height
    }
}
