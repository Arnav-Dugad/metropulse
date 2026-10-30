package io.github.arnavdugad.metropulse.core.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed interface InstallEvent {
    /** The system needs the user to confirm; launch [confirmIntent] from a visible activity. */
    data class NeedsConfirmation(val confirmIntent: Intent) : InstallEvent
    data object Succeeded : InstallEvent
    data object Cancelled : InstallEvent
    data class Failed(val message: String) : InstallEvent
}

/** Delivers [PackageInstaller] results from [InstallResultReceiver] to whoever is observing. */
object InstallEvents {
    private val _latest = MutableStateFlow<InstallEvent?>(null)
    val latest: StateFlow<InstallEvent?> = _latest.asStateFlow()

    internal fun post(event: InstallEvent) {
        _latest.value = event
    }

    /** Marks [event] handled so it is not replayed to the next collector. */
    fun consume(event: InstallEvent) {
        _latest.compareAndSet(event, null)
    }
}

/** Installs an APK through a [PackageInstaller] session (no FileProvider or ACTION_VIEW needed). */
class ApkInstaller @Inject constructor(@ApplicationContext private val context: Context) {

    fun canRequestInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // An app updating itself may skip the confirmation dialog on Android 12+.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("metropulse.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val callback = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, callback, flags).intentSender)
            }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw t
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val event = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return InstallEvents.post(InstallEvent.Failed("The installer did not respond"))
                InstallEvent.NeedsConfirmation(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> InstallEvent.Succeeded
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallEvent.Cancelled
            PackageInstaller.STATUS_FAILURE_STORAGE -> InstallEvent.Failed("Not enough storage to install the update")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallEvent.Failed("This update is not compatible with the installed app")
            else -> InstallEvent.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update could not be installed",
            )
        }
        InstallEvents.post(event)
    }
}
