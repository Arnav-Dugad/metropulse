package io.github.arnavdugad.metropulse.core.map.geometry

import io.github.arnavdugad.metropulse.core.domain.transit.TrackKind

/**
 * The height of the track along a pattern, so the map (and the trains on it) move smoothly between
 * viaduct, ground and tunnel instead of jumping:
 *
 * - a viaduct ramps down to the ground over [RAMP_M] (about 4 %), centred on where the mapped
 *   viaduct meets at-grade track;
 * - where the line goes underground it first descends into a walled cutting [TRENCH_M] long that
 *   ends at a tunnel [Portal]; a viaduct running straight into a tunnel ramps down before the cutting.
 *
 * Ramps and cuttings are shortened on short stretches so they never take more than [MAX_SHARE] of one.
 */
class ElevationProfile(spans: List<KindSpan>, val lengthM: Double) {

    /** A tunnel mouth at [atM]; the tunnel lies on the side given by [tunnelAhead]. */
    data class Portal(val atM: Double, val tunnelAhead: Boolean)

    /** A cutting between [fromM] and [toM] whose walls rise towards the portal end. */
    data class Trench(val fromM: Double, val toM: Double, val deepEndAtTo: Boolean)

    /** A stretch of raised track at one height; ramps are cut into short pieces. */
    data class Piece(val fromM: Double, val toM: Double, val top: Double)

    private class Viaduct(val from: Double, val to: Double, val riseStart: Double, val riseEnd: Double, val fallStart: Double, val fallEnd: Double)

    private val viaducts = ArrayList<Viaduct>()
    private val underground = spans.filter { it.kind == TrackKind.Underground }.map { it.fromM..it.toM }
    val portals: List<Portal>
    val trenches: List<Trench>

    init {
        val portals = ArrayList<Portal>()
        val trenches = ArrayList<Trench>()
        spans.forEachIndexed { i, span ->
            val len = span.toM - span.fromM
            val prev = spans.getOrNull(i - 1)?.kind
            val next = spans.getOrNull(i + 1)?.kind
            when (span.kind) {
                TrackKind.Elevated -> {
                    val budget = len * MAX_SHARE
                    // Start: centred ramp into at-grade track; descent + cutting before a tunnel.
                    val (riseStart, riseEnd) = when (prev) {
                        TrackKind.AtGrade -> (span.fromM - RAMP_M / 2) to (span.fromM + minOf(RAMP_M / 2, budget))
                        TrackKind.Underground -> {
                            val trench = minOf(TRENCH_M, budget / 3)
                            val ramp = minOf(RAMP_M, budget - trench)
                            (span.fromM + trench) to (span.fromM + trench + ramp)
                        }
                        TrackKind.Elevated, null -> span.fromM to span.fromM // a terminus on the viaduct
                    }
                    val (fallStart, fallEnd) = when (next) {
                        TrackKind.AtGrade -> (span.toM - minOf(RAMP_M / 2, budget)) to (span.toM + RAMP_M / 2)
                        TrackKind.Underground -> {
                            val trench = minOf(TRENCH_M, budget / 3)
                            val ramp = minOf(RAMP_M, budget - trench)
                            (span.toM - trench - ramp) to (span.toM - trench)
                        }
                        TrackKind.Elevated, null -> span.toM to span.toM
                    }
                    viaducts += Viaduct(span.fromM, span.toM, riseStart, riseEnd, fallStart, fallEnd)
                }
                TrackKind.Underground -> {
                    if (prev != null) {
                        portals += Portal(span.fromM, tunnelAhead = true)
                        val avail = (spans[i - 1].toM - spans[i - 1].fromM) * if (prev == TrackKind.Elevated) MAX_SHARE / 3 else MAX_SHARE
                        trenches += Trench(span.fromM - minOf(TRENCH_M, avail), span.fromM, deepEndAtTo = true)
                    }
                    if (next != null) {
                        portals += Portal(span.toM, tunnelAhead = false)
                        val avail = (spans[i + 1].toM - spans[i + 1].fromM) * if (next == TrackKind.Elevated) MAX_SHARE / 3 else MAX_SHARE
                        trenches += Trench(span.toM, span.toM + minOf(TRENCH_M, avail), deepEndAtTo = false)
                    }
                }
                TrackKind.AtGrade -> Unit
            }
        }
        this.portals = portals
        this.trenches = trenches
    }

    /** Height of the rail level above the ground at [alongM] (0 on the ground and in tunnels). */
    fun top(alongM: Double): Double {
        var h = 0.0
        for (v in viaducts) {
            val rise = ramp(alongM, v.riseStart, v.riseEnd)
            val fall = 1.0 - ramp(alongM, v.fallStart, v.fallEnd)
            val f = minOf(rise, fall)
            if (f > 0) h = maxOf(h, DECK_TOP * f)
        }
        return h
    }

    fun inTunnel(alongM: Double): Boolean = underground.any { alongM > it.start + 1 && alongM < it.endInclusive - 1 }

    /**
     * The raised track as pieces of constant height: long ones on the level, short ones (about
     * [STEP_M]) up and down the ramps, so a viaduct can be drawn as extrusions.
     */
    fun raisedPieces(): List<Piece> {
        val out = ArrayList<Piece>()
        for (v in viaducts) {
            val from = minOf(v.from, v.riseStart).coerceAtLeast(0.0)
            val to = maxOf(v.to, v.fallEnd).coerceAtMost(lengthM)
            var s = from
            while (s < to) {
                val flat = s >= v.riseEnd && s < v.fallStart
                val e = if (flat) v.fallStart else minOf(s + STEP_M, to)
                val h = if (flat) DECK_TOP else top((s + e) / 2)
                if (h > MIN_TOP) {
                    val last = out.lastOrNull()
                    if (last != null && last.top == h && last.toM >= s - 0.01) out[out.lastIndex] = last.copy(toM = e) else out += Piece(s, e, h)
                }
                s = e
            }
        }
        return out
    }

    private fun ramp(x: Double, start: Double, end: Double): Double = when {
        end <= start -> if (x >= start) 1.0 else 0.0
        x <= start -> 0.0
        x >= end -> 1.0
        else -> (x - start) / (end - start)
    }

    companion object {
        /** Top of the deck (rail level) on a viaduct, in metres. */
        const val DECK_TOP = 13.2
        const val RAMP_M = 330.0
        const val TRENCH_M = 160.0
        const val MAX_SHARE = 0.45
        const val STEP_M = 5.0
        private const val MIN_TOP = 0.15
    }
}
