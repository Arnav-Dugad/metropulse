package io.github.arnavdugad.metropulse.core.data.update

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.arnavdugad.metropulse.core.domain.update.RemoteFile
import io.github.arnavdugad.metropulse.core.domain.update.AppRelease
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads and verifies an update APK as a foreground job with a progress notification. */
@HiltWorker
class ApkDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val downloader: VerifiedDownloader,
    private val verifier: ApkVerifier,
    private val notifier: UpdateNotifier,
) : CoroutineWorker(context, params) {

    private val version get() = inputData.getString(KEY_VERSION).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val asset = RemoteFile(
            name = inputData.getString(KEY_NAME) ?: return Result.failure(),
            downloadUrl = inputData.getString(KEY_URL) ?: return Result.failure(),
            sizeBytes = inputData.getLong(KEY_SIZE, 0),
            sha256 = inputData.getString(KEY_SHA256),
            checksumUrl = inputData.getString(KEY_CHECKSUM_URL),
        )
        val directory = File(inputData.getString(KEY_DIRECTORY) ?: return Result.failure())
        runCatching { setForeground(foregroundInfo(0, asset.sizeBytes)) }

        return try {
            val apk = downloader.download(asset, directory) { downloaded, total ->
                setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to total))
                runCatching { setForeground(foregroundInfo(downloaded, total)) }
            }
            when (val verdict = verifier.verify(apk.file)) {
                ApkVerification.Valid -> {
                    notifier.showReadyToInstall(version)
                    Result.success(workDataOf(KEY_FILE to apk.file.absolutePath))
                }
                is ApkVerification.Invalid -> {
                    apk.file.delete()
                    Result.failure(workDataOf(KEY_ERROR to verdict.reason))
                }
            }
        } catch (e: ChecksumMismatchException) {
            Result.failure(workDataOf(KEY_ERROR to "The download was corrupted. Please try again."))
        } catch (e: IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "The download failed")))
        }
    }

    private fun foregroundInfo(downloaded: Long, total: Long): ForegroundInfo {
        val notification = notifier.downloadProgress(version, downloaded, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(UpdateNotifier.ID_DOWNLOAD, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(UpdateNotifier.ID_DOWNLOAD, notification)
        }
    }

    companion object {
        const val UNIQUE_NAME = "metropulse-apk-download"
        const val KEY_DOWNLOADED = "downloaded"
        const val KEY_TOTAL = "total"
        const val KEY_FILE = "file"
        const val KEY_ERROR = "error"
        private const val KEY_VERSION = "version"
        private const val KEY_NAME = "name"
        private const val KEY_URL = "url"
        private const val KEY_SIZE = "size"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_CHECKSUM_URL = "checksum_url"
        private const val KEY_DIRECTORY = "directory"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(workManager: WorkManager, release: AppRelease, directory: File) {
            val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_VERSION to release.version.toString(),
                        KEY_NAME to release.apk.name,
                        KEY_URL to release.apk.downloadUrl,
                        KEY_SIZE to release.apk.sizeBytes,
                        KEY_SHA256 to release.apk.sha256,
                        KEY_CHECKSUM_URL to release.apk.checksumUrl,
                        KEY_DIRECTORY to directory.absolutePath,
                    ),
                )
                .build()
            workManager.enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
