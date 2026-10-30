package io.github.arnavdugad.metropulse.onboard

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.R as DataR

/**
 * The "your stop is next" alert. It rings like an alarm, vibrates, and opens a full-screen card
 * over the lock screen, so it works with the phone in a pocket. An exact alarm is kept armed as a
 * backup in case Android stops the trip service.
 */
object WakeAlert {
    private const val CHANNEL = "wake_alert"
    private const val GROUP = "wake_alert"
    private const val NOTIFICATION_ID = 41
    private const val REQUEST_BACKUP = 42
    private const val REQUEST_OPEN = 43
    const val EXTRA_DESTINATION = "destination"
    private val VIBRATION = longArrayOf(0, 600, 300, 600, 300, 900)

    fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.onboard_channel_alert))
                .setDescription(context.getString(R.string.onboard_channel_alert_description))
                .setVibrationEnabled(true)
                .setVibrationPattern(VIBRATION)
                .setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
                .build(),
        )
    }

    fun show(context: Context, destination: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, WakeAlertActivity::class.java).putExtra(EXTRA_DESTINATION, destination)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canFullScreen = Build.VERSION.SDK_INT < 34 || context.getSystemService<NotificationManager>()!!.canUseFullScreenIntent()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.mp_ic_stat_train)
            .setColor(ContextCompat.getColor(context, DataR.color.mp_notification_accent))
            .setContentTitle(context.getString(R.string.onboard_alert_title))
            .setContentText(context.getString(R.string.onboard_alert_text, destination))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            // A group of its own: if Android bundled it with the trip notification it would be
            // silenced, and a silenced notification neither rings nor opens its full-screen card.
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setVibrate(VIBRATION)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply { if (canFullScreen) setFullScreenIntent(open, true) }
            .addAction(0, context.getString(R.string.onboard_alert_dismiss), open)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    /** Arms (or moves) the backup alarm. Exact when allowed; otherwise Android may delay it a little. */
    fun scheduleBackup(context: Context, atMillis: Long, destination: String) {
        val alarms = context.getSystemService<AlarmManager>() ?: return
        val pi = backupIntent(context, destination)
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancelBackup(context: Context) {
        context.getSystemService<AlarmManager>()?.cancel(backupIntent(context, ""))
    }

    private fun backupIntent(context: Context, destination: String): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_BACKUP,
        Intent(context, WakeAlertReceiver::class.java).putExtra(EXTRA_DESTINATION, destination),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/** Fires the wake alert from the backup alarm. */
class WakeAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WakeAlert.show(context, intent.getStringExtra(WakeAlert.EXTRA_DESTINATION).orEmpty())
    }
}
