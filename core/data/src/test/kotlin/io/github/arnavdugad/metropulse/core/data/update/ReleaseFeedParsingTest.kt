package io.github.arnavdugad.metropulse.core.data.update

import io.github.arnavdugad.metropulse.core.domain.update.SemVer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseFeedParsingTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val sha = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, assets: String = apkAsset()) = """
        {
          "tag_name": "$tag", "name": "", "draft": $draft, "prerelease": $prerelease,
          "body": "## What's new\r\n- Faster routing",
          "published_at": "2026-10-29T10:15:30Z",
          "html_url": "https://github.com/Arnav-Dugad/metropulse/releases/tag/$tag",
          "author": {"login": "someone"},
          "assets": [$assets]
        }
    """.trimIndent()

    private fun apkAsset(name: String = "metropulse-1.2.0.apk", digest: String? = "sha256:$sha") = """
        {"name": "$name", "size": 1234,
         "content_type": "application/vnd.android.package-archive",
         "browser_download_url": "https://github.com/x/$name"
         ${digest?.let { """, "digest": "$it"""" } ?: ""}}
    """.trimIndent()

    @Test
    fun `parses a GitHub release`() {
        val r = ReleaseFeed.parse(json, release("v1.2.0"))
        assertNotNull(r)
        r!!
        assertEquals(SemVer(1, 2, 0), r.version)
        assertEquals("MetroPulse 1.2.0", r.title)
        assertEquals("## What's new\n- Faster routing", r.notes)
        assertEquals(1234L, r.apk.sizeBytes)
        assertEquals(sha, r.apk.sha256)
        assertEquals(java.time.Instant.parse("2026-10-29T10:15:30Z").toEpochMilli(), r.publishedAtEpochMillis)
    }

    @Test
    fun `drafts, bad tags and releases without an APK are ignored`() {
        assertNull(ReleaseFeed.parse(json, release("v1.2.0", draft = true)))
        assertNull(ReleaseFeed.parse(json, release("nightly")))
        assertNull(ReleaseFeed.parse(json, release("v1.2.0", assets = """{"name":"notes.txt","browser_download_url":"u"}""")))
    }

    @Test
    fun `GitHub pre-release flag marks a stable-looking tag as pre-release`() {
        assertTrue(ReleaseFeed.parse(json, release("v1.3.0", prerelease = true))!!.version.isPreRelease)
    }

    @Test
    fun `falls back to a sha256 companion asset when no digest is present`() {
        val assets = apkAsset(digest = null) + "," +
            """{"name":"metropulse-1.2.0.apk.sha256","browser_download_url":"https://github.com/x/sum"}"""
        val apk = ReleaseFeed.parse(json, release("v1.2.0", assets = assets))!!.apk
        assertNull(apk.sha256)
        assertEquals("https://github.com/x/sum", apk.checksumUrl)
    }
}
