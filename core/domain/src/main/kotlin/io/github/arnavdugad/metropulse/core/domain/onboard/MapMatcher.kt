package io.github.arnavdugad.metropulse.core.domain.onboard

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class GpsFix(val timeMillis: Long, val location: LatLon, val accuracyM: Double)

/** Where the rider's train is believed to be. */
data class MatchState(
    val patternId: Int,
    val alongM: Double,
    val speedMps: Double,
    /** Distance of the last accepted fix from the track. */
    val offsetM: Double,
    /** 0..1: how sure the matcher is about the pattern and position. */
    val confidence: Double,
    val timeMillis: Long,
)

/**
 * Snaps GPS fixes to the track of one of the candidate patterns (usually both directions of the
 * boarded line). Each candidate keeps a constant-velocity Kalman filter over (position along the
 * line, speed). Fixes far from the track, with poor accuracy or implying impossible speed are
 * rejected, and a candidate whose train would have to run backwards loses to the other direction.
 */
class MapMatcher(private val patterns: List<Pattern>) {

    private class Hypothesis(val pattern: Pattern) {
        var s = 0.0
        var v = 0.0
        var p00 = 0.0
        var p01 = 0.0
        var p11 = 0.0
        var timeMillis = 0L
        var accepted = 0
        var misses = 0
        var score = 0.0 // lower is better
        var offset = 0.0
        var initialised = false
    }

    private val hypotheses = patterns.map { Hypothesis(it) }

    fun reset() {
        hypotheses.forEach { it.initialised = false; it.accepted = 0; it.misses = 0; it.score = 0.0 }
    }

    /** Feeds a fix; returns the best estimate, or null while nothing matches yet. */
    fun update(fix: GpsFix): MatchState? {
        if (fix.accuracyM <= MAX_ACCURACY_M) {
            for (h in hypotheses) step(h, fix)
        }
        return best(fix.timeMillis)
    }

    /** The current estimate advanced to [timeMillis] without a new fix. */
    fun predict(timeMillis: Long): MatchState? = best(timeMillis)

    private fun step(h: Hypothesis, fix: GpsFix) {
        val gate = max(MIN_GATE_M, GATE_ACCURACY_FACTOR * fix.accuracyM)
        val r = max(fix.accuracyM, MIN_ACCURACY_M).let { it * it }
        if (!h.initialised) {
            val proj = h.pattern.shape.project(fix.location)
            if (proj.offsetM > gate) return
            h.s = proj.alongM; h.v = 0.0
            h.p00 = r; h.p01 = 0.0; h.p11 = INITIAL_SPEED_VARIANCE
            h.timeMillis = fix.timeMillis; h.offset = proj.offsetM
            h.accepted = 1; h.misses = 0; h.score = (proj.offsetM / max(fix.accuracyM, MIN_ACCURACY_M)).let { it * it }
            h.initialised = true
            return
        }
        val dt = ((fix.timeMillis - h.timeMillis) / 1000.0).coerceAtLeast(0.0)
        predictInPlace(h, dt)
        // Only look near the predicted position, so a looping line cannot make the train jump.
        val window = MAX_SPEED_MPS * dt + 3 * sqrt(h.p00) + WINDOW_SLACK_M
        val proj = h.pattern.shape.project(fix.location, h.s - window, h.s + window)
        val innovation = proj.alongM - h.s
        val impliedSpeed = if (dt > 0) abs(innovation) / dt else 0.0
        val sVar = h.p00 + r
        val outlier = proj.offsetM > gate ||
            (abs(innovation) > 4 * sqrt(sVar) && impliedSpeed > MAX_SPEED_MPS)
        if (outlier) {
            h.misses++
            h.score += MISS_PENALTY
            if (h.misses >= MAX_MISSES) h.initialised = false
            return
        }
        val k0 = h.p00 / sVar
        val k1 = h.p01 / sVar
        h.s += k0 * innovation
        h.v += k1 * innovation
        val p00 = (1 - k0) * h.p00
        val p01 = (1 - k0) * h.p01
        val p11 = h.p11 - k1 * h.p01
        h.p00 = p00; h.p01 = p01; h.p11 = p11
        if (h.v < -REVERSE_TOLERANCE_MPS) h.score += WRONG_DIRECTION_PENALTY
        h.v = h.v.coerceAtLeast(0.0) // a train does not reverse along its pattern
        h.s = h.s.coerceIn(0.0, h.pattern.shape.lengthM)
        h.offset = proj.offsetM
        h.accepted++
        h.misses = 0
        val residual = proj.offsetM / max(fix.accuracyM, MIN_ACCURACY_M)
        h.score = SCORE_DECAY * h.score + residual * residual
    }

    private fun predictInPlace(h: Hypothesis, dt: Double) {
        if (dt <= 0) return
        val q = ACCEL_SIGMA * ACCEL_SIGMA
        h.s = (h.s + h.v * dt).coerceIn(0.0, h.pattern.shape.lengthM)
        val p00 = h.p00 + dt * (2 * h.p01 + dt * h.p11) + q * dt * dt * dt * dt / 4
        val p01 = h.p01 + dt * h.p11 + q * dt * dt * dt / 2
        val p11 = h.p11 + q * dt * dt
        h.p00 = p00; h.p01 = p01; h.p11 = p11
        h.timeMillis += (dt * 1000).toLong()
    }

    private fun best(timeMillis: Long): MatchState? {
        val live = hypotheses.filter { it.initialised }
        if (live.isEmpty()) return null
        val h = live.minWith(compareBy<Hypothesis> { it.score }.thenByDescending { it.accepted })
        val dt = ((timeMillis - h.timeMillis) / 1000.0).coerceAtLeast(0.0)
        val along = (h.s + h.v * dt).coerceIn(0.0, h.pattern.shape.lengthM)
        val others = live.filter { it !== h }.minOfOrNull { it.score } ?: Double.MAX_VALUE
        val separation = if (others == Double.MAX_VALUE) 1.0 else (others - h.score).coerceIn(0.0, 10.0) / 10.0
        val maturity = (h.accepted / 5.0).coerceAtMost(1.0)
        return MatchState(h.pattern.id, along, h.v, h.offset, maturity * (0.5 + 0.5 * separation), timeMillis)
    }

    private companion object {
        const val MAX_ACCURACY_M = 200.0
        const val MIN_ACCURACY_M = 5.0
        const val MIN_GATE_M = 75.0
        const val GATE_ACCURACY_FACTOR = 2.5
        const val MAX_SPEED_MPS = 40.0 // faster than any Indian metro (Airport lines top out near 33 m/s)
        const val WINDOW_SLACK_M = 250.0
        const val ACCEL_SIGMA = 0.7
        const val INITIAL_SPEED_VARIANCE = 100.0
        const val REVERSE_TOLERANCE_MPS = 1.5
        const val WRONG_DIRECTION_PENALTY = 4.0
        const val MISS_PENALTY = 2.0
        const val MAX_MISSES = 6
        const val SCORE_DECAY = 0.8
    }
}
