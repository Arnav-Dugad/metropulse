package io.github.arnavdugad.metropulse.core.map.geometry

import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.Track
import io.github.arnavdugad.metropulse.core.domain.transit.TrackKind
import kotlin.math.floor
import kotlin.math.sqrt

/** A stretch of a pattern with one kind of track. */
data class KindSpan(val fromM: Double, val toM: Double, val kind: TrackKind)

/**
 * Splits each pattern into elevated / underground / at-grade stretches by looking up the nearest
 * OpenStreetMap track piece of the same line every few metres. Gaps in the mapping take the kind
 * of their surroundings, and very short flips (a level crossing, a mapping slip) are smoothed away.
 */
class TrackProfile(tracks: List<Track>) {
    private val origin = tracks.firstOrNull()?.geometry?.firstOrNull()
    private val plane = origin?.let(::LocalPlane)
    private val grid = HashMap<Long, MutableList<Seg>>()

    private class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double, val kind: TrackKind, val lines: List<String>)

    init {
        if (plane != null) {
            for (t in tracks) {
                for (i in 0 until t.geometry.size - 1) {
                    val s = Seg(plane.x(t.geometry[i]), plane.y(t.geometry[i]), plane.x(t.geometry[i + 1]), plane.y(t.geometry[i + 1]), t.kind, t.lineIds)
                    for (key in cellsAlong(s)) grid.getOrPut(key) { ArrayList() } += s
                }
            }
        }
    }

    fun spans(pattern: Pattern): List<KindSpan> {
        val length = pattern.shape.lengthM
        if (plane == null || length <= 0) return listOf(KindSpan(0.0, length, TrackKind.AtGrade))
        val n = (length / SAMPLE_M).toInt() + 1
        val kinds = arrayOfNulls<TrackKind>(n)
        for (i in 0 until n) {
            val p = pattern.shape.pointAt(i * SAMPLE_M)
            kinds[i] = nearestKind(plane.x(p), plane.y(p), pattern.lineId)
        }
        if (kinds.all { it == null }) return listOf(KindSpan(0.0, length, TrackKind.AtGrade))
        fillGaps(kinds)
        val runs = runs(kinds.map { it!! })
        val smoothed = smooth(runs)
        return smoothed.map { (from, to, kind) -> KindSpan(from * SAMPLE_M, minOf(to * SAMPLE_M, length), kind) }
            .let { spans -> spans.dropLast(1) + spans.last().copy(toM = length) }
    }

    private fun nearestKind(x: Double, y: Double, lineId: String): TrackKind? {
        val cx = floor(x / CELL_M).toInt()
        val cy = floor(y / CELL_M).toInt()
        var best: Seg? = null
        var bestD = MATCH_M
        for (dx in -1..1) for (dy in -1..1) {
            for (s in grid[key(cx + dx, cy + dy)].orEmpty()) {
                if (s.lines.isNotEmpty() && lineId !in s.lines) continue
                val d = distance(x, y, s)
                if (d < bestD) { bestD = d; best = s }
            }
        }
        return best?.kind
    }

    /** Unmatched samples take the kind of the nearest matched sample. */
    private fun fillGaps(kinds: Array<TrackKind?>) {
        val known = kinds.indices.filter { kinds[it] != null }
        for (i in kinds.indices) {
            if (kinds[i] == null) kinds[i] = kinds[known.minBy { kotlin.math.abs(it - i) }]
        }
    }

    private fun runs(kinds: List<TrackKind>): MutableList<Triple<Int, Int, TrackKind>> {
        val out = ArrayList<Triple<Int, Int, TrackKind>>()
        var start = 0
        for (i in 1..kinds.size) {
            if (i == kinds.size || kinds[i] != kinds[start]) {
                out += Triple(start, i, kinds[start])
                start = i
            }
        }
        return out
    }

    /** Merges runs shorter than [MIN_RUN_M] into their longer neighbour. */
    private fun smooth(runs: MutableList<Triple<Int, Int, TrackKind>>): List<Triple<Int, Int, TrackKind>> {
        val minSamples = (MIN_RUN_M / SAMPLE_M).toInt()
        var changed = true
        while (changed && runs.size > 1) {
            changed = false
            val i = runs.indices.minBy { runs[it].second - runs[it].first }
            val r = runs[i]
            if (r.second - r.first >= minSamples) break
            val prev = runs.getOrNull(i - 1)
            val next = runs.getOrNull(i + 1)
            val into = when {
                prev == null -> next!!.third
                next == null -> prev.third
                prev.second - prev.first >= next.second - next.first -> prev.third
                else -> next.third
            }
            runs[i] = Triple(r.first, r.second, into)
            // Merge neighbours of equal kind.
            val merged = ArrayList<Triple<Int, Int, TrackKind>>()
            for (run in runs) {
                val last = merged.lastOrNull()
                if (last != null && last.third == run.third) merged[merged.size - 1] = Triple(last.first, run.second, run.third) else merged += run
            }
            runs.clear(); runs += merged
            changed = true
        }
        return runs
    }

    private fun cellsAlong(s: Seg): List<Long> {
        val x0 = floor(minOf(s.ax, s.bx) / CELL_M).toInt()
        val x1 = floor(maxOf(s.ax, s.bx) / CELL_M).toInt()
        val y0 = floor(minOf(s.ay, s.by) / CELL_M).toInt()
        val y1 = floor(maxOf(s.ay, s.by) / CELL_M).toInt()
        val out = ArrayList<Long>()
        for (cx in x0..x1) for (cy in y0..y1) out += key(cx, cy)
        return out
    }

    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    private fun distance(x: Double, y: Double, s: Seg): Double {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((x - s.ax) * dx + (y - s.ay) * dy) / len2).coerceIn(0.0, 1.0)
        val px = s.ax + t * dx - x
        val py = s.ay + t * dy - y
        return sqrt(px * px + py * py)
    }

    private companion object {
        const val SAMPLE_M = 15.0
        const val CELL_M = 60.0
        const val MATCH_M = 35.0
        const val MIN_RUN_M = 90.0
    }
}
