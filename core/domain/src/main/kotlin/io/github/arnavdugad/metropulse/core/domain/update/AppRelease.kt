package io.github.arnavdugad.metropulse.core.domain.update

/** A published app release, as described by the release feed. */
data class AppRelease(
    val version: SemVer,
    val tag: String,
    val title: String,
    /** Release notes in Markdown. */
    val notes: String,
    val publishedAtEpochMillis: Long?,
    val pageUrl: String,
    val apk: RemoteFile,
)

/** A file to download and verify: an update APK or a city database. */
data class RemoteFile(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the file, when the publisher provides one. */
    val sha256: String?,
    /** A companion `<file>.sha256` asset, used when there is no inline digest. */
    val checksumUrl: String? = null,
)
