package io.github.arnavdugad.metropulse.core.data.update

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
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Daily background check that posts a notification once per new release. */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: UpdateManager,
    private val prefs: UpdatePreferences,
    private val notifier: UpdateNotifier,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!prefs.autoCheck.first()) return Result.success()
        val state = manager.check(userInitiated = false)
        if (state is UpdateState.Available && !state.skipped && prefs.claimNotification(state.release.version)) {
            notifier.showAvailable(state.release)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "metropulse-update-check"

        /** Schedules or cancels the daily check to match the user's setting. */
        fun sync(workManager: WorkManager, enabled: Boolean) {
            if (!enabled) {
                workManager.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS, 4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
