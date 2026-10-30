package io.github.arnavdugad.metropulse.onboard

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.R as DataR
import io.github.arnavdugad.metropulse.core.domain.onboard.PositionSource
import io.github.arnavdugad.metropulse.core.domain.onboard.TripPhase
import io.github.arnavdugad.metropulse.core.domain.onboard.epochMillisOf
import java.util.Date
import kotlin.math.roundToInt

/**
 * The ongoing trip notification. Where Android supports it (16+) it is a promoted Live Update: a
 * progress bar of the journey with one segment per ride in the line's colour, points at changes
 * and the destination, a train moving along it, and the minutes left as a status bar chip.
 */
object TripNotifications {
    const val ID = 40
    // Default importance (so the trip sits with other ongoing activity, not under "Silent") but no
    // sound or vibration: updates arrive every few seconds.
    private const val CHANNEL = "trip_progress"
    private const val GROUP = "trip"
    private const val PROGRESS_MAX = 1000
    private const val REQUEST_OPEN = 44
    private const val REQUEST_STOP = 45

    fun ensureChannel(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.onboard_channel_trip))
                .setDescription(context.getString(R.string.onboard_channel_trip_description))
                .setSound(null, null)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build(),
        )
    }

    fun build(context: Context, session: TripSession?): Notification {
        ensureChannel(context)
        if (session == null) {
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.mp_ic_stat_train)
                .setContentTitle(context.getString(R.string.onboard_starting))
                .setOngoing(true).setSilent(true)
                .build()
        }
        val text = describe(context, session)
        return notification(context, session, text)
    }

    private class Texts(val title: String, val body: String, val source: String, val chip: String?)

    private fun describe(context: Context, s: TripSession): Texts {
        val p = s.progress
        val network = s.network
        val line = network.lineById[p.ride.lineId]
        val minutes = p.secondsToDestination?.let { ((it + 30) / 60).coerceAtLeast(1) }
        val source = context.getString(
            when (p.source) {
                PositionSource.Live -> R.string.onboard_source_live
                PositionSource.Scheduled -> R.string.onboard_source_scheduled
                PositionSource.Approximate -> R.string.onboard_source_approximate
            },
        )
        return when (p.phase) {
            TripPhase.Arrived -> Texts(context.getString(R.string.onboard_arrived_title, s.toName), context.getString(R.string.onboard_arrived_body), "", null)
            TripPhase.WaitingToBoard -> {
                val from = network.stationById[p.ride.fromStationId]?.name.orEmpty()
                val departs = p.ride.departSecs?.let { secs ->
                    DateFormat.getTimeFormat(context).format(Date(epochMillisOf(s.journey.serviceDate, secs, network.timezone)))
                }
                Texts(
                    context.getString(R.string.onboard_board_title, line?.name.orEmpty(), p.ride.headsign),
                    if (departs != null) context.getString(R.string.onboard_board_departs, from, departs) else context.getString(R.string.onboard_board_untimed, from),
                    source,
                    departs,
                )
            }
            TripPhase.Riding -> {
                val next = network.patternById[p.ride.patternId]?.stationIds?.getOrNull(p.nextStopIndex)?.let { network.stationById[it]?.name }.orEmpty()
                val stops = context.resources.getQuantityString(R.plurals.onboard_stops, p.stopsToDestination, p.stopsToDestination)
                Texts(
                    context.getString(R.string.onboard_riding_title, s.toName),
                    if (minutes != null) context.getString(R.string.onboard_riding_body, stops, minutes, next) else context.getString(R.string.onboard_riding_body_untimed, stops, next),
                    source,
                    minutes?.let { context.getString(R.string.onboard_chip_minutes, it) },
                )
            }
        }
    }

    /**
     * One builder for every version: NotificationCompat applies the Live Update parts (progress
     * segments and points, promotion, status bar chip) only where the platform supports them, and
     * falls back to a plain progress bar elsewhere.
     */
    private fun notification(context: Context, s: TripSession, t: Texts): Notification {
        val rides = s.journey.rides
        val total = rides.sumOf { it.distanceM }.coerceAtLeast(1.0)
        val accent = ContextCompat.getColor(context, DataR.color.mp_notification_accent)
        val style = NotificationCompat.ProgressStyle().setStyledByProgress(false)
        var done = 0
        rides.forEachIndexed { i, ride ->
            val length = if (i == rides.lastIndex) PROGRESS_MAX - done else (ride.distanceM / total * PROGRESS_MAX).roundToInt().coerceAtLeast(1)
            val colour = s.network.lineById[ride.lineId]?.color?.let { 0xFF000000.toInt() or it } ?: accent
            style.addProgressSegment(NotificationCompat.ProgressStyle.Segment(length).setColor(colour))
            done += length
            if (i < rides.lastIndex) style.addProgressPoint(NotificationCompat.ProgressStyle.Point(done).setColor(colour))
        }
        style.addProgressPoint(NotificationCompat.ProgressStyle.Point(PROGRESS_MAX))
        style.setProgress((s.progress.fraction * PROGRESS_MAX).roundToInt())
        style.setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.mp_ic_progress_train))
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.mp_ic_stat_train)
            .setColor(accent)
            .setContentTitle(t.title)
            .setContentText(t.body)
            .setSubText(t.source)
            .setStyle(style)
            .setOngoing(s.progress.phase != TripPhase.Arrived)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(GROUP) // never bundled with the wake alert (see WakeAlert)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.onboard_end_trip), stopTrip(context))
            .setRequestPromotedOngoing(true)
            .apply { t.chip?.let(::setShortCriticalText) }
            .build()
    }

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, REQUEST_OPEN, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopTrip(context: Context): PendingIntent = PendingIntent.getService(
        context, REQUEST_STOP, Intent(context, OnboardService::class.java).setAction(OnboardService.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
