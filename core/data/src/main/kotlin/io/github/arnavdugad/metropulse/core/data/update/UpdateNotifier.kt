package io.github.arnavdugad.metropulse.core.data.update

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.core.data.R
import io.github.arnavdugad.metropulse.core.domain.update.AppRelease
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_UPDATES, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.mp_channel_updates))
                    .setDescription(context.getString(R.string.mp_channel_updates_description))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_DOWNLOAD, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.mp_channel_update_download))
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    fun showAvailable(release: AppRelease) = post(
        ID_AVAILABLE,
        builder(CHANNEL_UPDATES)
            .setContentTitle(context.getString(R.string.mp_update_available_title, release.version.toString()))
            .setContentText(context.getString(R.string.mp_update_available_text))
            .setAutoCancel(true)
            .build(),
    )

    fun showReadyToInstall(version: String) = post(
        ID_AVAILABLE,
        builder(CHANNEL_UPDATES)
            .setContentTitle(context.getString(R.string.mp_update_ready_title, version))
            .setContentText(context.getString(R.string.mp_update_ready_text))
            .setAutoCancel(true)
            .build(),
    )

    fun downloadProgress(version: String, downloaded: Long, total: Long): Notification =
        builder(CHANNEL_DOWNLOAD)
            .setContentTitle(context.getString(R.string.mp_update_downloading_title, version))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(
                if (total > 0) 1000 else 0,
                if (total > 0) (downloaded * 1000 / total).toInt() else 0,
                total <= 0,
            )
            .build()

    private fun builder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.mp_ic_stat_update)
        .setColor(ContextCompat.getColor(context, R.color.mp_notification_accent))
        .setContentIntent(openUpdateSheet())

    private fun openUpdateSheet(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.putExtra(EXTRA_SHOW_UPDATE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context, REQUEST_OPEN, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            manager.notify(id, notification)
        }
    }

    companion object {
        /** Intent extra on the launcher activity that opens the update sheet. */
        const val EXTRA_SHOW_UPDATE = "io.github.arnavdugad.metropulse.SHOW_UPDATE"
        const val ID_DOWNLOAD = 41
        private const val ID_AVAILABLE = 40
        private const val REQUEST_OPEN = 40
        private const val CHANNEL_UPDATES = "updates"
        private const val CHANNEL_DOWNLOAD = "update_download"
    }
}
