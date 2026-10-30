package io.github.arnavdugad.metropulse.core.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private fun release(version: String) = AppRelease(
        version = SemVer.parse(version)!!,
        tag = "v$version",
        title = "MetroPulse $version",
        notes = "",
        publishedAtEpochMillis = null,
        pageUrl = "https://example.invalid",
        apk = RemoteFile("metropulse.apk", "https://example.invalid/a.apk", 1, null),
    )

    private val installed = SemVer.parse("1.2.0")!!

    @Test
    fun `newer stable release is offered`() {
        assertTrue(UpdatePolicy.decide(installed, release("1.3.0"), null) is UpdateDecision.Available)
    }

    @Test
    fun `same or older release is up to date`() {
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(installed, release("1.2.0"), null))
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(installed, release("1.1.9"), null))
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(installed, null, null))
    }

    @Test
    fun `pre-release is not offered to stable users`() {
        assertEquals(UpdateDecision.UpToDate, UpdatePolicy.decide(installed, release("1.3.0-beta.1"), null))
    }

    @Test
    fun `skipped version is reported but a later one is offered again`() {
        val skipped = SemVer.parse("1.3.0")
        assertTrue(UpdatePolicy.decide(installed, release("1.3.0"), skipped) is UpdateDecision.Skipped)
        assertTrue(UpdatePolicy.decide(installed, release("1.3.1"), skipped) is UpdateDecision.Available)
    }
}
