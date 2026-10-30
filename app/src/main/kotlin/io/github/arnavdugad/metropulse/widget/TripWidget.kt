package io.github.arnavdugad.metropulse.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.LocalSize
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.domain.onboard.PositionSource
import io.github.arnavdugad.metropulse.core.domain.onboard.TripPhase
import io.github.arnavdugad.metropulse.launch.LaunchAction
import io.github.arnavdugad.metropulse.onboard.TripSession
import io.github.arnavdugad.metropulse.ui.common.clockFormatter
import java.time.ZonedDateTime

/** The trip in progress (destination, stops left, arrival), or a button to start one. */
class TripWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val sessions = context.widgetDependencies().onboard().session
        provideContent {
            val session by sessions.collectAsState()
            Box(
                GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)).padding(14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                val s = session
                if (s == null) NoTrip() else Trip(s)
            }
        }
    }

    @Composable
    private fun Trip(s: TripSession) {
        val context = LocalContext.current
        val p = s.progress
        val line = s.network.lineById[p.ride.lineId]
        val colour = line?.let { lineColour(it.color) } ?: WidgetColors.accent
        val open = actionStartActivity(context.packageManager.getLaunchIntentForPackage(context.packageName)!!)
        Column(GlanceModifier.fillMaxSize().clickable(open), verticalAlignment = Alignment.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Dot(colour, 12.dp)
                Spacer(GlanceModifier.width(8.dp))
                WidgetText(
                    if (p.phase == TripPhase.Arrived) context.getString(R.string.onboard_arrived_title, s.toName)
                    else context.getString(R.string.onboard_riding_title, s.toName),
                    size = 15.sp,
                    bold = true,
                    modifier = GlanceModifier.defaultWeight(),
                )
                WidgetTag(
                    context.getString(
                        when (p.source) {
                            PositionSource.Live -> R.string.onboard_source_live
                            PositionSource.Scheduled -> R.string.onboard_source_scheduled
                            PositionSource.Approximate -> R.string.onboard_source_approximate
                        },
                    ),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            val detail = when {
                p.phase == TripPhase.Arrived -> context.getString(R.string.onboard_arrived_body)
                p.phase == TripPhase.WaitingToBoard -> {
                    val from = s.network.stationById[p.ride.fromStationId]?.name.orEmpty()
                    val departs = p.ride.departSecs
                    if (departs != null) context.getString(R.string.widget_trip_board, line?.name.orEmpty(), from, clockFormatter(context)(departs))
                    else context.getString(R.string.widget_trip_board_untimed, line?.name.orEmpty(), from)
                }
                else -> {
                    val stops = context.resources.getQuantityString(R.plurals.onboard_stops, p.stopsToDestination, p.stopsToDestination)
                    val secs = p.secondsToDestination
                    if (secs == null) stops else {
                        val arrive = ZonedDateTime.now(s.network.timezone).toLocalTime().toSecondOfDay() + secs
                        context.getString(R.string.widget_trip_arrive, stops, clockFormatter(context)(arrive))
                    }
                }
            }
            WidgetText(detail, color = WidgetColors.textSecondary, maxLines = 2)
            if (p.phase == TripPhase.Riding && LocalSize.current.height >= 100.dp) {
                Spacer(GlanceModifier.height(10.dp))
                Strip(s, colour)
            }
            Spacer(GlanceModifier.height(10.dp))
            LinearProgressIndicator(
                progress = p.fraction.toFloat(),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = colour,
                backgroundColor = WidgetColors.surface,
            )
        }
    }

    /** The current ride's stops as a mini line: passed stops filled, the rest hollow (at most [MAX_STOPS]). */
    @Composable
    private fun Strip(s: TripSession, colour: androidx.glance.unit.ColorProvider) {
        val p = s.progress
        val ride = p.ride
        val first = ride.boardIndex
        val last = ride.alightIndex
        val total = last - first + 1
        // A window that keeps the train in view on long rides.
        val start = if (total <= MAX_STOPS) first else (p.nextStopIndex - MAX_STOPS / 2).coerceIn(first, last - MAX_STOPS + 1)
        val end = minOf(last, start + MAX_STOPS - 1)
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for (i in start..end) {
                val passed = i < p.nextStopIndex
                if (i > start) Box(GlanceModifier.defaultWeight().height(3.dp).background(if (passed) colour else WidgetColors.surface)) {}
                Dot(if (passed || i == last) colour else WidgetColors.textSecondary, if (i == last) 12.dp else 8.dp)
            }
        }
    }

    @Composable
    private fun NoTrip() {
        val context = LocalContext.current
        Row(
            GlanceModifier.fillMaxSize().clickable(actionStartActivity(LaunchAction.intent(context, LaunchAction.OnBoard))),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(GlanceModifier.defaultWeight()) {
                WidgetText(context.getString(R.string.widget_no_trip), size = 15.sp, bold = true)
                WidgetText(context.getString(R.string.widget_no_trip_body), color = WidgetColors.textSecondary, maxLines = 2)
            }
            Spacer(GlanceModifier.width(10.dp))
            Box(
                GlanceModifier.background(ImageProvider(R.drawable.widget_button)).padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WidgetIcon(R.drawable.ic_widget_train, WidgetColors.onAccent, 16.dp)
                    Spacer(GlanceModifier.width(6.dp))
                    WidgetText(context.getString(R.string.home_on_board), color = WidgetColors.onAccent, bold = true)
                }
            }
        }
    }
}

class TripWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TripWidget()
}

private const val MAX_STOPS = 9
