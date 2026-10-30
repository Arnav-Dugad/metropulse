package io.github.arnavdugad.metropulse.core.data.city

import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.data.update.VerifiedDownloader
import io.github.arnavdugad.metropulse.core.domain.city.CityDataPolicy
import io.github.arnavdugad.metropulse.core.domain.city.CityDataUpdate
import io.github.arnavdugad.metropulse.core.domain.city.CityInfo
import io.github.arnavdugad.metropulse.core.domain.update.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps city databases current from the manifest in the GitHub repository. No server is involved:
 * the manifest and databases are static files served by raw.githubusercontent.com.
 */
@Singleton
class CityDataUpdater @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val info: AppBuildInfo,
    private val files: CityFiles,
    private val downloader: VerifiedDownloader,
) {
    /** All cities the remote manifest lists (for showing cities not bundled in this APK). */
    suspend fun remoteCities(): List<CityInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(info.dataManifestUrl).header("Cache-Control", "no-cache").build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Data manifest unavailable (HTTP ${r.code})")
            CityManifest.parse(json, r.body.string())
        }
    }

    /** Newer data for cities already on the device. */
    suspend fun availableUpdates(): List<CityDataUpdate> =
        CityDataPolicy.updatesFor(remoteCities(), files.installedVersions())

    /** Downloads, verifies and installs one city's database. Returns false if verification failed. */
    suspend fun install(city: CityInfo): Boolean {
        val staging = File(files.directory, "staging").apply { mkdirs() }
        val downloaded = downloader.download(
            RemoteFile(name = "${city.id}.db", downloadUrl = city.url, sizeBytes = city.sizeBytes, sha256 = city.sha256),
            staging,
        ) { _, _ -> }
        if (!files.verifyDatabase(downloaded.file, city)) {
            downloaded.file.delete()
            return false
        }
        files.install(downloaded.file, city)
        return true
    }

    /** Applies every available update; returns the ids of cities that were updated. */
    suspend fun updateAll(): List<String> = availableUpdates().filter { install(it.city) }.map { it.city.id }
}
