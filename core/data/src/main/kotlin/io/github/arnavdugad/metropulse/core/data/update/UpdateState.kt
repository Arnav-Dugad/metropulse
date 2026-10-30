package io.github.arnavdugad.metropulse.core.data.update

import io.github.arnavdugad.metropulse.core.domain.update.AppRelease
import java.io.File

sealed interface UpdateState {
    /** This build cannot self-update (debug build without a test feed). */
    data object Disabled : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val release: AppRelease, val skipped: Boolean) : UpdateState
    data class Downloading(val release: AppRelease, val downloaded: Long, val total: Long) : UpdateState {
        /** 0..1, or null while the size is unknown. */
        val fraction: Float? get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class ReadyToInstall(val release: AppRelease, val apk: File) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val release: AppRelease?, val message: String) : UpdateState
}

val UpdateState.release: AppRelease?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.ReadyToInstall -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        else -> null
    }
