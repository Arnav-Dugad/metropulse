package io.github.arnavdugad.metropulse.core.domain.update

/**
 * A semantic version (`major.minor.patch[-prerelease]`), ordered per semver 2.0.0 precedence.
 * Build metadata (`+...`) is accepted and ignored, as the spec requires.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<SemVer> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch).let { if (it != 0) return it }
        // A version without a pre-release tag outranks any pre-release of the same core version.
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) {
            return other.preRelease.size.coerceAtMost(1) - preRelease.size.coerceAtMost(1)
        }
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifiers(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else preRelease.joinToString(".", prefix = "-")

    companion object {
        private val PATTERN = Regex("""^[vV]?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        /** Parses `1.2.3`, `v1.2.3`, `1.2` and `1.2.3-beta.1`; returns null for anything else. */
        fun parse(raw: String): SemVer? {
            val m = PATTERN.matchEntire(raw.trim()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return SemVer(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = if (patch.isEmpty()) 0 else patch.toIntOrNull() ?: return null,
                preRelease = if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }

        private fun compareIdentifiers(a: String, b: String): Int {
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            return when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1 // numeric identifiers rank below alphanumeric ones
                bn != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
