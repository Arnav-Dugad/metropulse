package io.github.arnavdugad.metropulse.core.data.update

import io.github.arnavdugad.metropulse.core.domain.update.RemoteFile
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class VerifiedDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer()
    private val downloader = VerifiedDownloader(OkHttpClient())
    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val payloadSha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun asset(sha: String? = payloadSha) =
        RemoteFile("metropulse.apk", server.url("/app.apk").toString(), payload.size.toLong(), sha)

    @Test
    fun `downloads and verifies the checksum`() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(payload)).build())
        var lastProgress = 0L
        val result = downloader.download(asset(), tmp.root) { downloaded, _ -> lastProgress = downloaded }
        assertArrayEquals(payload, result.file.readBytes())
        assertTrue(result.checksumVerified)
        assertEquals(payload.size.toLong(), lastProgress)
    }

    @Test
    fun `resumes a partial download with a Range request`() = runTest {
        val half = payload.size / 2
        tmp.root.resolve("metropulse.apk.part").writeBytes(payload.copyOfRange(0, half))
        server.enqueue(
            MockResponse.Builder()
                .code(206)
                .addHeader("Content-Range", "bytes $half-${payload.size - 1}/${payload.size}")
                .body(okio.Buffer().write(payload.copyOfRange(half, payload.size)))
                .build(),
        )
        val result = downloader.download(asset(), tmp.root) { _, _ -> }
        assertEquals("bytes=$half-", server.takeRequest().headers["Range"])
        assertArrayEquals(payload, result.file.readBytes())
    }

    @Test
    fun `rejects a file whose checksum does not match`() = runTest {
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(payload)).build())
        try {
            downloader.download(asset(sha = "0".repeat(64)), tmp.root) { _, _ -> }
            fail("expected a checksum failure")
        } catch (expected: ChecksumMismatchException) {
            assertFalse(tmp.root.resolve("metropulse.apk").exists())
            assertFalse(tmp.root.resolve("metropulse.apk.part").exists())
        }
    }
}
