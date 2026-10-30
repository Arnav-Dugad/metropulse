package io.github.arnavdugad.metropulse.core.data.update

import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.domain.update.AppRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the latest release from the GitHub Releases API. Unauthenticated, so it is limited to
 * 60 requests an hour per IP; conditional requests (ETag) answered with 304 do not count.
 */
@Singleton
class ReleaseFeed @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val info: AppBuildInfo,
) {
    private var etag: String? = null
    private var cached: AppRelease? = null

    /** Returns the latest release, null if none is published, or throws [IOException]. */
    suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(info.updateFeedUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> cached
                response.code == 404 -> null // the repository has no published release yet
                response.code == 403 || response.code == 429 -> throw IOException("Update server is busy, try again later")
                !response.isSuccessful -> throw IOException("Update check failed (HTTP ${response.code})")
                else -> parse(json, response.body.string()).also {
                    etag = response.header("ETag")
                    cached = it
                }
            }
        }
    }

    internal companion object {
        fun parse(json: Json, body: String): AppRelease? =
            json.decodeFromString(GitHubReleaseDto.serializer(), body).toDomain()
    }
}
