package io.github.arnavdugad.metropulse.core.domain.search

import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

data class SearchEntry(val complexId: String, val name: String)

data class SearchHit(val complexId: String, val name: String, val score: Double)

/**
 * Typo-tolerant station search. A city has at most a few hundred stations, so every name is
 * scored in memory: exact, prefix, word prefix, substring, per-word Damerau-Levenshtein (so
 * "rajiv chok" and "kashmiri gate" still match) and, last, trigram similarity.
 */
class StationSearch(entries: List<SearchEntry>) {
    private class Indexed(val entry: SearchEntry, val norm: String, val compact: String, val tokens: List<String>, val trigrams: Set<String>)

    private val index = entries.map { e ->
        val n = normalize(e.name)
        Indexed(e, n, n.replace(" ", ""), n.split(' ').filter { it.isNotEmpty() }, trigrams(n))
    }

    /**
     * Stations matching [query], best first. [usage] (complex id -> how often the rider uses it) lifts
     * familiar stations within a match tier, never above a better kind of match.
     */
    fun search(query: String, limit: Int = 10, usage: Map<String, Int> = emptyMap()): List<SearchHit> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val qTokens = q.split(' ').filter { it.isNotEmpty() }
        val qCompact = q.replace(" ", "")
        val qTrigrams = trigrams(q)
        return index.asSequence()
            .map { it to score(it, q, qCompact, qTokens, qTrigrams) }
            .filter { it.second > 0.0 }
            .map { (ix, s) -> ix to s + boost(usage[ix.entry.complexId] ?: 0) }
            .sortedWith(compareByDescending<Pair<Indexed, Double>> { it.second }.thenBy { it.first.entry.name.length }.thenBy { it.first.entry.name })
            .take(limit)
            .map { (ix, s) -> SearchHit(ix.entry.complexId, ix.entry.name, s) }
            .toList()
    }

    /** At most 0.04: less than the gap between match tiers (exact, prefix, word prefix...). */
    private fun boost(uses: Int): Double = if (uses <= 0) 0.0 else min(0.04, 0.01 * kotlin.math.ln(1.0 + uses) / kotlin.math.ln(2.0))

    private fun score(ix: Indexed, q: String, qCompact: String, qTokens: List<String>, qTrigrams: Set<String>): Double {
        if (ix.norm == q) return 1.0
        if (ix.norm.startsWith(q)) return 0.95
        if (ix.compact.startsWith(qCompact)) return 0.9
        if (ix.tokens.any { it.startsWith(q) }) return 0.85
        if (ix.norm.contains(q) || ix.compact.contains(qCompact)) return 0.75

        var sum = 0.0
        for (qt in qTokens) {
            val best = ix.tokens.maxOfOrNull { tokenSimilarity(qt, it) } ?: 0.0
            if (best == 0.0) { sum = -1.0; break }
            sum += best
        }
        if (sum > 0) return 0.5 + 0.3 * sum / qTokens.size

        val shared = qTrigrams.count { it in ix.trigrams }
        val jaccard = shared.toDouble() / (qTrigrams.size + ix.trigrams.size - shared).coerceAtLeast(1)
        return if (jaccard >= 0.35) 0.4 * jaccard else 0.0
    }

    /** 1.0 for a prefix, less for a word within typo tolerance (also of a partly typed word), else 0. */
    private fun tokenSimilarity(q: String, token: String): Double {
        if (token.startsWith(q)) return 1.0
        val tolerance = when {
            q.length <= 2 -> 0
            q.length <= 5 -> 1
            else -> 2
        }
        if (tolerance == 0) return 0.0
        val d = min(damerauLevenshtein(q, token), damerauLevenshtein(q, token.take(q.length)))
        return if (d <= tolerance) 1.0 - d.toDouble() / max(q.length, 1) else 0.0
    }

    companion object {
        private val ABBREVIATIONS = mapOf("rd" to "road", "stn" to "station", "sec" to "sector", "mkt" to "market", "jn" to "junction", "jct" to "junction")

        fun forNetwork(network: TransitNetwork): StationSearch =
            StationSearch(network.complexes.map { (id, members) -> SearchEntry(id, members.first().name) })

        fun normalize(text: String): String {
            val folded = Normalizer.normalize(text, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
            val cleaned = folded.replace("&", " and ").replace(Regex("[.'’]"), "").replace(Regex("[^a-z0-9]+"), " ")
            return cleaned.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { ABBREVIATIONS[it] ?: it }
        }

        private fun trigrams(s: String): Set<String> {
            val padded = "  $s "
            return (0..padded.length - 3).mapTo(HashSet()) { padded.substring(it, it + 3) }
        }

        /** Optimal string alignment distance (insertions, deletions, substitutions, adjacent swaps). */
        fun damerauLevenshtein(a: String, b: String): Int {
            val d = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) d[i][0] = i
            for (j in 0..b.length) d[0][j] = j
            for (i in 1..a.length) {
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
            return d[a.length][b.length]
        }
    }
}
