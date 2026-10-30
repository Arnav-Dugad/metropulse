package io.github.arnavdugad.metropulse.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.arnavdugad.metropulse.MainActivity
import io.github.arnavdugad.metropulse.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Leave now" reminders: a notification shortly before a planned train. Alarms are set while
 * idle-allowed; exact when the app may, otherwise Android may deliver them a few minutes late.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    private const val EXTRA_ID = "id"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_TEXT = "text"

    fun schedule(context: Context, id: Int, atMillis: Long, title: String, text: String) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, id, title, text)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    fun cancel(context: Context, id: Int) {
        context.getSystemService(AlarmManager::class.java)?.cancel(pending(context, id, "", ""))
    }

    private fun pending(context: Context, id: Int, title: String, text: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_ID, id).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    internal fun show(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.reminder_channel_description)
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.mp_ic_stat_train)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify("reminder", id, notification)
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getIntExtra(EXTRA_ID, 0)
            val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            if (id != CommuteReminder.ID) {
                show(context, id, title, intent.getStringExtra(EXTRA_TEXT).orEmpty())
                return
            }
            // The commute reminder lists the next trains, so it is written when it fires.
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    CommuteReminder.text(context)?.let { show(context, id, title, it) }
                    CommuteReminder.onFired(context, id)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
