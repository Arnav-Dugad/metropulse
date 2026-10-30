package io.github.arnavdugad.metropulse.core.data.update

import io.github.arnavdugad.metropulse.core.domain.update.RemoteFile
import io.github.arnavdugad.metropulse.core.domain.update.AppRelease
import io.github.arnavdugad.metropulse.core.domain.update.SemVer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** The subset of GitHub's `GET /repos/{owner}/{repo}/releases/latest` response that the updater reads. */
@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String? = null,
    /** e.g. `sha256:9f86d0...`; GitHub computes this for every uploaded asset. */
    val digest: String? = null,
)

private const val APK_MIME = "application/vnd.android.package-archive"
private val HEX_64 = Regex("^[0-9a-f]{64}$")

/** Maps a release DTO to the domain model, or null when it is a draft, unversioned or has no APK. */
internal fun GitHubReleaseDto.toDomain(): AppRelease? {
    if (draft) return null
    val version = SemVer.parse(tagName) ?: return null
    val apk = assets
        .filter { it.name.endsWith(".apk", ignoreCase = true) || it.contentType == APK_MIME }
        .minByOrNull { if (it.name.contains("metropulse", ignoreCase = true)) 0 else 1 }
        ?: return null
    val checksumAsset = assets.firstOrNull { it.name.equals("${apk.name}.sha256", ignoreCase = true) }
    return AppRelease(
        // A release flagged as pre-release on GitHub is treated as one even if its tag looks stable.
        version = if (prerelease && !version.isPreRelease) version.copy(preRelease = listOf("pre")) else version,
        tag = tagName,
        title = name?.takeIf { it.isNotBlank() } ?: "MetroPulse $version",
        notes = body.orEmpty().replace("\r\n", "\n").trim(),
        publishedAtEpochMillis = publishedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        pageUrl = htmlUrl,
        apk = RemoteFile(
            name = apk.name,
            downloadUrl = apk.downloadUrl,
            sizeBytes = apk.size,
            sha256 = apk.digest?.lowercase()?.removePrefix("sha256:")?.takeIf { HEX_64.matches(it) },
            checksumUrl = checksumAsset?.downloadUrl,
        ),
    )
}
