package io.github.arnavdugad.metropulse.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.settings.AppSettings
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.transit.Departures
import io.github.arnavdugad.metropulse.ui.common.clockFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * A weekday reminder at the rider's chosen time: "Time to leave · next trains from Whitefield to
 * Majestic: 8:02, 8:07, 8:12", worked out when it fires. Re-armed for the next weekday each time,
 * and after a reboot.
 */
object CommuteReminder {
    const val ID = 7_001

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun settings(): AppSettings
        fun cities(): CityRepository
    }

    private fun deps(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)

    /** Arms (or disarms) the next reminder from the saved settings. */
    suspend fun sync(context: Context) {
        val settings = deps(context).settings().current()
        if (!settings.commuteReminder) {
            Reminders.cancel(context, ID)
            return
        }
        val now = ZonedDateTime.now()
        var at = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(settings.commuteReminderMinutes.toLong())
        while (at <= now || at.dayOfWeek == DayOfWeek.SATURDAY || at.dayOfWeek == DayOfWeek.SUNDAY) at = at.plusDays(1)
        Reminders.schedule(context, ID, at.toInstant().toEpochMilli(), context.getString(R.string.commute_reminder_title), "")
    }

    /** Called by [Reminders.ReminderReceiver] after it posted a reminder. */
    internal fun onFired(context: Context, id: Int) {
        if (id != ID) return
        CoroutineScope(Dispatchers.Default).launch { sync(context) }
    }

    /** The reminder's text, with the next trains on the commute leg for now. */
    internal suspend fun text(context: Context): String? {
        val d = deps(context)
        val network = withTimeoutOrNull(8_000) { d.cities().state.first { it !is CityState.Loading } }.let { (it as? CityState.Ready)?.city?.network } ?: return null
        val commute = d.settings().commute(network.cityId).first() ?: return null
        val now = ZonedDateTime.now(network.timezone)
        val (from, to) = commute.leg(now.hour)
        val toStations = network.complexes[to].orEmpty().mapTo(HashSet()) { it.id }
        val clock = clockFormatter(context)
        // Departures from home whose line goes on to work.
        val times = Departures.at(network, from, now.toLocalDate(), now.toLocalTime().toSecondOfDay(), count = 3)
            .filter { g -> network.patterns.any { p -> p.lineId == g.line.id && p.direction == g.direction && p.stationIds.any { it in toStations } } }
            .flatMap { it.next }.map { it.secs }.sorted().take(3)
        val names = context.getString(R.string.commute_reminder_leg, network.complexName(from), network.complexName(to))
        return if (times.isEmpty()) names else context.getString(R.string.commute_reminder_text, names, times.joinToString(", ") { clock(it) })
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val pending = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    sync(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
