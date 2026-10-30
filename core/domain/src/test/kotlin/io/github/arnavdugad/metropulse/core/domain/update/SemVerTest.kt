package io.github.arnavdugad.metropulse.core.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {

    private fun v(s: String) = requireNotNull(SemVer.parse(s)) { s }

    @Test
    fun `parses tags with and without prefix`() {
        assertEquals(SemVer(1, 2, 3), v("v1.2.3"))
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(1, 2, 0), v("1.2"))
        assertEquals(SemVer(1, 0, 0, listOf("beta", "2")), v("1.0.0-beta.2+build.7"))
    }

    @Test
    fun `rejects garbage`() {
        listOf("", "latest", "1", "1.2.3.4", "v1.x.0", "1.2.3-").forEach { assertNull(it, SemVer.parse(it)) }
    }

    @Test
    fun `orders by semver precedence`() {
        val ordered = listOf(
            "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "1.10.0", "2.0.0",
        ).map(::v)
        ordered.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", a < b) }
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
    }

    @Test
    fun `round trips to string`() {
        assertEquals("1.0.0-rc.1", v("v1.0.0-rc.1").toString())
    }
}
