package io.github.arnavdugad.metropulse

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import io.github.arnavdugad.metropulse.core.data.AppBuildInfo
import io.github.arnavdugad.metropulse.core.data.di.ApplicationScope
import io.github.arnavdugad.metropulse.core.data.update.UpdateCheckWorker
import io.github.arnavdugad.metropulse.core.data.update.UpdateManager
import io.github.arnavdugad.metropulse.core.data.update.UpdatePreferences
import io.github.arnavdugad.metropulse.core.map.MetroMapInit
import io.github.arnavdugad.metropulse.launch.SurfaceSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class MetroPulseApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var buildInfo: AppBuildInfo
    @Inject lateinit var updatePreferences: UpdatePreferences
    @Inject lateinit var updateManager: UpdateManager
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope
    @Inject lateinit var httpClient: OkHttpClient
    @Inject lateinit var surfaceSync: SurfaceSync

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        MetroMapInit.init(this, httpClient)
        surfaceSync.start()
        if (buildInfo.updatesEnabled) {
            updateManager.cleanupInstalledDownloads()
            appScope.launch {
                updatePreferences.autoCheck.distinctUntilChanged().collect { enabled ->
                    UpdateCheckWorker.sync(WorkManager.getInstance(this@MetroPulseApplication), enabled)
                }
            }
        }
    }
}
