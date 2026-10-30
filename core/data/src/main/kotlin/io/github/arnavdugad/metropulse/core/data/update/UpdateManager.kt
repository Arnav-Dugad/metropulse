package io.github.arnavdugad.metropulse.core.data.update

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.data.di.ApplicationScope
import io.github.arnavdugad.metropulse.core.domain.update.SemVer
import io.github.arnavdugad.metropulse.core.domain.update.UpdateDecision
import io.github.arnavdugad.metropulse.core.domain.update.UpdatePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the self-update flow:
 * check -> (skip | download -> verify -> install).
 * Downloads run in [ApkDownloadWorker] so they survive the update sheet being dismissed.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    private val info: AppBuildInfo,
    private val feed: ReleaseFeed,
    private val prefs: UpdatePreferences,
    private val installer: ApkInstaller,
    private val workManager: WorkManager,
) {
    private val installedVersion: SemVer = SemVer.parse(info.versionName) ?: SemVer(0, 0, 0)
    private val checkLock = Mutex()

    private val _state = MutableStateFlow<UpdateState>(if (info.updatesEnabled) UpdateState.Idle else UpdateState.Disabled)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val updatesDirectory: File get() = File(context.cacheDir, "updates")

    init {
        if (info.updatesEnabled) {
            scope.launch { workManager.getWorkInfosForUniqueWorkFlow(ApkDownloadWorker.UNIQUE_NAME).collect(::onDownloadWork) }
            scope.launch { InstallEvents.latest.filterNotNull().collect(::onInstallEvent) }
        }
    }

    /**
     * Fetches the latest release. A background check never replaces an in-flight download or a
     * visible error with its own result; a user-initiated one always reports back.
     */
    suspend fun check(userInitiated: Boolean): UpdateState = checkLock.withLock {
        if (!info.updatesEnabled) return UpdateState.Disabled
        val current = _state.value
        if (current is UpdateState.Downloading || current is UpdateState.ReadyToInstall || current is UpdateState.Installing) {
            return current
        }
        if (userInitiated) _state.value = UpdateState.Checking
        val result = try {
            val latest = feed.latest()
            val now = System.currentTimeMillis()
            prefs.markChecked(now)
            when (val decision = UpdatePolicy.decide(installedVersion, latest, prefs.skippedVersion())) {
                UpdateDecision.UpToDate -> UpdateState.UpToDate(now)
                is UpdateDecision.Available -> UpdateState.Available(decision.release, skipped = false)
                // A version the user skipped is still shown when they explicitly ask.
                is UpdateDecision.Skipped ->
                    if (userInitiated) UpdateState.Available(decision.release, skipped = true) else UpdateState.UpToDate(now)
            }
        } catch (e: IOException) {
            if (!userInitiated) return current
            UpdateState.Failed(null, e.message ?: "Could not reach the update server")
        } catch (e: kotlinx.serialization.SerializationException) {
            if (!userInitiated) return current
            UpdateState.Failed(null, "The update server sent an unexpected response")
        }
        _state.value = result
        result
    }

    fun startDownload() {
        val release = _state.value.release ?: return
        _state.value = UpdateState.Downloading(release, 0, release.apk.sizeBytes)
        ApkDownloadWorker.enqueue(workManager, release, updatesDirectory)
    }

    fun cancelDownload() {
        workManager.cancelUniqueWork(ApkDownloadWorker.UNIQUE_NAME)
        _state.value.release?.let { _state.value = UpdateState.Available(it, skipped = false) }
    }

    fun install() {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        _state.value = UpdateState.Installing(ready.release)
        scope.launch {
            try {
                installer.install(ready.apk)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(ready.release, e.message ?: "The update could not be installed")
            }
        }
    }

    fun canRequestInstalls(): Boolean = installer.canRequestInstalls()

    suspend fun skip() {
        val release = _state.value.release ?: return
        prefs.skip(release.version)
        _state.value = UpdateState.Available(release, skipped = true)
    }

    /** Clears an error so the sheet can offer the release again. */
    fun dismissError() {
        _state.update { if (it is UpdateState.Failed) it.release?.let { r -> UpdateState.Available(r, false) } ?: UpdateState.Idle else it }
    }

    /** Deletes APKs that are already installed (or older); called on every launch. */
    fun cleanupInstalledDownloads() = scope.launch(Dispatchers.IO) {
        val installedCode = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        updatesDirectory.listFiles { f -> f.name.endsWith(".apk") }?.forEach { apk ->
            @Suppress("DEPRECATION")
            val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
            if (archive == null || PackageInfoCompat.getLongVersionCode(archive) <= installedCode) apk.delete()
        }
        // Abandoned partial downloads older than a day are not worth resuming.
        val dayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        updatesDirectory.listFiles { f -> f.name.endsWith(".part") && f.lastModified() < dayAgo }?.forEach { it.delete() }
    }

    private suspend fun onDownloadWork(infos: List<WorkInfo>) {
        val work = infos.firstOrNull() ?: return
        val release = _state.value.release ?: run {
            // The process restarted mid-download; recover the release details from the feed.
            if (!work.state.isFinished) check(userInitiated = false)
            _state.value.release
        } ?: return
        when (work.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                if (_state.value is UpdateState.Installing || _state.value is UpdateState.ReadyToInstall) return
                val downloaded = work.progress.getLong(ApkDownloadWorker.KEY_DOWNLOADED, 0)
                val total = work.progress.getLong(ApkDownloadWorker.KEY_TOTAL, release.apk.sizeBytes)
                _state.value = UpdateState.Downloading(release, downloaded, total)
            }
            WorkInfo.State.SUCCEEDED -> {
                val path = work.outputData.getString(ApkDownloadWorker.KEY_FILE) ?: return
                if (_state.value !is UpdateState.Installing) _state.value = UpdateState.ReadyToInstall(release, File(path))
            }
            WorkInfo.State.FAILED -> _state.value = UpdateState.Failed(
                release,
                work.outputData.getString(ApkDownloadWorker.KEY_ERROR) ?: "The download failed",
            )
            WorkInfo.State.CANCELLED -> Unit
        }
    }

    private fun onInstallEvent(event: InstallEvent) {
        val current = _state.value
        val release = current.release ?: return
        when (event) {
            is InstallEvent.NeedsConfirmation -> Unit // the UI launches the system dialog
            InstallEvent.Succeeded -> Unit // the process is replaced by the new version
            InstallEvent.Cancelled -> {
                InstallEvents.consume(event)
                File(updatesDirectory, release.apk.name).takeIf { it.exists() }
                    ?.let { _state.value = UpdateState.ReadyToInstall(release, it) }
                    ?: run { _state.value = UpdateState.Available(release, false) }
            }
            is InstallEvent.Failed -> {
                InstallEvents.consume(event)
                _state.value = UpdateState.Failed(release, event.message)
            }
        }
    }

}
