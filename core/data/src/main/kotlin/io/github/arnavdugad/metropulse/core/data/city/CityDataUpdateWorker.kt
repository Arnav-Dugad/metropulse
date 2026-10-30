package io.github.arnavdugad.metropulse.core.data.city

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Weekly refresh of installed city databases. Data files are small (well under 1 MB each). */
@HiltWorker
class CityDataUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: CityDataUpdater,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        updater.updateAll()
        Result.success()
    } catch (e: IOException) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    } catch (e: kotlinx.serialization.SerializationException) {
        Result.failure()
    }

    companion object {
        private const val UNIQUE_NAME = "metropulse-city-data-update"

        fun sync(workManager: WorkManager, enabled: Boolean) {
            if (!enabled) {
                workManager.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<CityDataUpdateWorker>(7, TimeUnit.DAYS, 1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build(),
                )
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
