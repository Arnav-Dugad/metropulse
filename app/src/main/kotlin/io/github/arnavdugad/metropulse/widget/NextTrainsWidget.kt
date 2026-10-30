package io.github.arnavdugad.metropulse.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.transit.DepartureGroup
import io.github.arnavdugad.metropulse.core.domain.transit.Departures
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.user.SavedStation
import io.github.arnavdugad.metropulse.launch.LaunchAction
import io.github.arnavdugad.metropulse.ui.common.clockFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Next trains from one of the rider's favourite stations, as timetable clock times (which stay
 * right between refreshes, unlike countdowns). Tapping the station name moves to the next favourite.
 * The widget refreshes itself when its first listed train has left.
 */
class NextTrainsWidget : GlanceAppWidget() {

    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val deps = context.widgetDependencies()
        val city = deps.readyCity()
        val network = city?.network
        val favourites = network?.let { n -> deps.user().favouriteStations(n.cityId).first().filter { n.complexes.containsKey(it.complexId) } }.orEmpty()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val chosen = currentState<Preferences>()[STATION]
            val station = favourites.firstOrNull { it.complexId == chosen } ?: favourites.firstOrNull()
            Column(
                GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_background)).padding(14.dp),
            ) {
                when {
                    network == null -> Message(R.string.widget_no_city, LaunchAction.Settings)
                    station == null -> Message(R.string.widget_no_favourites, LaunchAction.Search)
                    else -> StationDepartures(network, station, favourites, appWidgetId)
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.StationDepartures(network: TransitNetwork, station: SavedStation, favourites: List<SavedStation>, appWidgetId: Int) {
        val context = LocalContext.current
        val clock = clockFormatter(context)
        val now = ZonedDateTime.now(network.timezone)
        val nowSecs = now.toLocalTime().toSecondOfDay()
        val groups = Departures.at(network, station.complexId, now.toLocalDate(), nowSecs, count = 3)
        val next = groups.mapNotNull { g -> g.next.firstOrNull()?.secs }.minOrNull()
        LaunchedEffect(station.complexId, next) {
            val midnight = now.toLocalDate().atStartOfDay(network.timezone).toInstant().toEpochMilli()
            WidgetRefresh.schedule(context, appWidgetId, next?.let { midnight + it * 1000L + 15_000 })
        }
        val open = actionStartActivity(LaunchAction.intent(context, LaunchAction.Station(network.cityId, station.complexId)))
        val cycle = if (favourites.size > 1) {
            val nextFavourite = favourites[(favourites.indexOfFirst { it.complexId == station.complexId } + 1) % favourites.size]
            actionRunCallback<ShowStation>(actionParametersOf(ShowStation.KEY to nextFavourite.complexId))
        } else {
            open
        }
        Row(GlanceModifier.fillMaxWidth().clickable(cycle), verticalAlignment = Alignment.CenterVertically) {
            WidgetIcon(R.drawable.ic_widget_train)
            Spacer(GlanceModifier.width(8.dp))
            WidgetText(network.complexName(station.complexId), size = 15.sp, bold = true, modifier = GlanceModifier.defaultWeight())
            if (favourites.size > 1) {
                Spacer(GlanceModifier.width(6.dp))
                WidgetIcon(R.drawable.ic_widget_swap, WidgetColors.textSecondary, 16.dp)
            }
        }
        Spacer(GlanceModifier.height(8.dp))
        if (groups.isEmpty()) {
            WidgetText(context.getString(R.string.station_no_departures), color = WidgetColors.textSecondary, maxLines = 2)
        } else {
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                items(groups, itemId = { g -> (g.line.id + g.headsign).hashCode().toLong() }) { g ->
                    Column(GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(open)) {
                        GroupRow(g, clock)
                    }
                }
            }
            val approximate = groups.any { it.timed && it.line.quality == DataQuality.Approximate }
            val shortTrips = groups.any { g -> g.next.any { it.headsign != g.headsign } }
            Row(GlanceModifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                WidgetTag(context.getString(if (approximate) R.string.onboard_source_approximate else R.string.onboard_source_scheduled))
                if (shortTrips) {
                    Spacer(GlanceModifier.width(8.dp))
                    WidgetText(context.getString(R.string.widget_short_trips), size = 11.sp, color = WidgetColors.textSecondary)
                }
            }
        }
    }

    @Composable
    private fun GroupRow(g: DepartureGroup, clock: (Int) -> String) {
        val context = LocalContext.current
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Dot(lineColour(g.line.color))
            Spacer(GlanceModifier.width(8.dp))
            WidgetText(context.getString(R.string.line_towards, g.headsign), color = WidgetColors.textSecondary, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            when {
                !g.timed -> WidgetText(context.getString(R.string.lines_times_unavailable), color = WidgetColors.textSecondary)
                g.next.isEmpty() -> WidgetText(context.getString(R.string.widget_service_ended), color = WidgetColors.textSecondary)
                // Trains that turn back before the terminus are faded (see the note below the list).
                else -> g.next.forEachIndexed { i, d ->
                    if (i > 0) Spacer(GlanceModifier.width(8.dp))
                    val full = d.headsign == g.headsign
                    WidgetText(clock(d.secs), bold = full, color = if (full) WidgetColors.textPrimary else WidgetColors.textSecondary)
                }
            }
        }
    }

    @Composable
    private fun Message(text: Int, action: LaunchAction) {
        val context = LocalContext.current
        Column(
            GlanceModifier.fillMaxSize().clickable(actionStartActivity(LaunchAction.intent(context, action))),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            WidgetIcon(R.drawable.ic_widget_train, size = 24.dp)
            Spacer(GlanceModifier.height(8.dp))
            WidgetText(context.getString(text), color = WidgetColors.textSecondary, maxLines = 3)
        }
    }

    companion object {
        internal val STATION = stringPreferencesKey("station")
    }
}

/** Switches a widget to another favourite station. */
class ShowStation : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val complexId = parameters[KEY] ?: return
        updateAppWidgetState(context, glanceId) { it[NextTrainsWidget.STATION] = complexId }
        NextTrainsWidget().update(context, glanceId)
    }

    companion object {
        val KEY = ActionParameters.Key<String>("station")
    }
}

class NextTrainsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextTrainsWidget()
}

/**
 * Refreshes the next-trains widgets when a listed train has left. The alarm does not wake the
 * phone; it fires the next time the phone is awake, which is when anyone can see the widget.
 */
class WidgetRefresh : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                NextTrainsWidget().updateAll(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** [atMillis] null: nothing more today; the provider's 30-minute update takes over. */
        fun schedule(context: Context, appWidgetId: Int, atMillis: Long?) {
            val alarms = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, WidgetRefresh::class.java)
            val pi = PendingIntent.getBroadcast(context, appWidgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (atMillis == null) {
                alarms.cancel(pi)
            } else {
                alarms.set(AlarmManager.RTC, atMillis.coerceAtLeast(System.currentTimeMillis() + 30_000), pi)
            }
        }
    }
}
