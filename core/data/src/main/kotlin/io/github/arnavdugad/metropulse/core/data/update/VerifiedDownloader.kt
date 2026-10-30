package io.github.arnavdugad.metropulse.core.data.update

import io.github.arnavdugad.metropulse.core.domain.update.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

class ChecksumMismatchException(expected: String, actual: String) :
    IOException("Downloaded file is corrupt (expected $expected, got $actual)")

data class DownloadedFile(val file: File, val sha256: String, val checksumVerified: Boolean)

/**
 * Streams a file (update APK or city database) to disk and checks its SHA-256. A partial download
 * is kept as `<name>.part` and resumed with an HTTP Range request, so a dropped connection on a
 * metro platform does not restart a 20 MB download.
 */
class VerifiedDownloader @Inject constructor(private val client: OkHttpClient) {

    suspend fun download(
        asset: RemoteFile,
        directory: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val expected = asset.sha256 ?: asset.checksumUrl?.let { fetchChecksum(it) }
        val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(directory, safeName)
        val partial = File(directory, "$safeName.part")

        if (target.exists() && expected != null && sha256(target) == expected) {
            return@withContext DownloadedFile(target, expected, checksumVerified = true)
        }
        target.delete()

        var offset = partial.takeIf { it.exists() }?.length() ?: 0L
        val request = Request.Builder().url(asset.downloadUrl)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()

        client.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> Unit
                200 -> offset = 0 // server ignored the Range header; start over
                416 -> { partial.delete(); throw IOException("Download could not be resumed, please retry") }
                else -> throw IOException("Download failed (HTTP ${response.code})")
            }
            val total = when {
                asset.sizeBytes > 0 -> asset.sizeBytes
                response.body.contentLength() > 0 -> offset + response.body.contentLength()
                else -> -1L
            }
            var downloaded = offset
            var lastReported = -1L
            FileOutputStream(partial, offset > 0).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        // Report roughly every 1% (or 256 KB when the size is unknown).
                        val step = if (total > 0) total / 100 else 256L * 1024
                        if (downloaded - lastReported >= step) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            onProgress(downloaded, total)
            if (asset.sizeBytes > 0 && downloaded != asset.sizeBytes) {
                partial.delete()
                throw IOException("Download was incomplete, please retry")
            }
        }

        val actual = sha256(partial)
        if (expected != null && actual != expected) {
            partial.delete()
            throw ChecksumMismatchException(expected, actual)
        }
        if (!partial.renameTo(target)) throw IOException("Could not save the update")
        DownloadedFile(target, actual, checksumVerified = expected != null)
    }

    private fun fetchChecksum(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            // `sha256sum` format: "<hex>  <file name>"
            Regex("[0-9a-fA-F]{64}").find(r.body.string())?.value?.lowercase()
        }
    }.getOrNull()

    internal companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
