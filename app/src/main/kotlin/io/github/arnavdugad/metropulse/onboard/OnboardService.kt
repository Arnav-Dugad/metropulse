package io.github.arnavdugad.metropulse.onboard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.arnavdugad.metropulse.core.domain.onboard.TripPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps on-board mode running with the screen off: a location foreground service that feeds GPS
 * fixes to the [OnboardController] and keeps the trip notification current. It uses the platform
 * location APIs only (the fused provider where Android has one, otherwise GPS), so it works on
 * phones without Google Play services. Fixes stay on the device.
 */
@AndroidEntryPoint
class OnboardService : Service() {

    @Inject lateinit var controller: OnboardController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listener = LocationListenerCompat { controller.onLocation(it) }
    private var requestedIntervalMs = 0L
    private var lastNotified: String? = null
    private var arrivedAt: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = TripNotifications.build(this, controller.session.value)
        ServiceCompat.startForeground(
            this, TripNotifications.ID, notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) controller.stop()
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        while (scope.isActive) {
            val session = controller.session.value
            if (session == null) {
                stopSelf()
                return
            }
            requestLocation(controller.desiredIntervalMs())
            notifyIfChanged(session)
            if (session.progress.phase == TripPhase.Arrived) {
                val since = arrivedAt ?: System.currentTimeMillis().also { arrivedAt = it }
                if (System.currentTimeMillis() - since > ARRIVED_LINGER_MS) controller.stop()
            }
            delay(TICK_MS)
        }
    }

    /** Re-posts the notification only when what it shows changes (stops, minutes, phase, source). */
    private fun notifyIfChanged(session: TripSession) {
        val p = session.progress
        val key = "${p.phase}/${p.rideIndex}/${p.stopsToDestination}/${p.secondsToDestination?.div(30)}/${p.source}/${(p.fraction * 100).toInt()}"
        if (key == lastNotified) return
        lastNotified = key
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(TripNotifications.ID, TripNotifications.build(this, session))
        }
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission
    private fun requestLocation(intervalMs: Long) {
        if (intervalMs == requestedIntervalMs || !hasLocationPermission(this)) return
        val manager = getSystemService<LocationManager>() ?: return
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        LocationManagerCompat.removeUpdates(manager, listener)
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        LocationManagerCompat.requestLocationUpdates(manager, provider, request, ContextCompat.getMainExecutor(this), listener)
        requestedIntervalMs = intervalMs
    }

    override fun onDestroy() {
        getSystemService<LocationManager>()?.let { LocationManagerCompat.removeUpdates(it, listener) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "io.github.arnavdugad.metropulse.onboard.STOP"
        private const val TICK_MS = 1_000L
        private const val ARRIVED_LINGER_MS = 60_000L

        fun hasLocationPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** A location foreground service needs the location permission; without it the trip runs on the timetable. */
        fun start(context: Context) {
            if (hasLocationPermission(context)) ContextCompat.startForegroundService(context, Intent(context, OnboardService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OnboardService::class.java))
        }
    }
}
