package io.github.arnavdugad.metropulse.launch

import android.content.Context
import android.content.Intent
import io.github.arnavdugad.metropulse.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something a shortcut, widget or the Quick Settings tile asked the app to open. */
sealed interface LaunchAction {
    data object Search : LaunchAction
    data object Plan : LaunchAction
    data object OnBoard : LaunchAction
    data object StartCommute : LaunchAction
    data object Settings : LaunchAction
    data class PlanJourney(val cityId: String, val fromComplexId: String, val toComplexId: String) : LaunchAction
    data class Station(val cityId: String, val complexId: String) : LaunchAction

    companion object {
        private const val ACTION_PREFIX = "io.github.arnavdugad.metropulse.action."
        private const val EXTRA_CITY = "city"
        private const val EXTRA_FROM = "from"
        private const val EXTRA_TO = "to"
        private const val EXTRA_STATION = "station"

        /** An explicit intent for [MainActivity]; also usable in shortcuts and pending intents. */
        fun intent(context: Context, action: LaunchAction): Intent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            when (action) {
                Search -> intent.action = ACTION_PREFIX + "SEARCH"
                Plan -> intent.action = ACTION_PREFIX + "PLAN"
                OnBoard -> intent.action = ACTION_PREFIX + "ON_BOARD"
                StartCommute -> intent.action = ACTION_PREFIX + "START_COMMUTE"
                Settings -> intent.action = ACTION_PREFIX + "SETTINGS"
                is PlanJourney -> {
                    intent.action = ACTION_PREFIX + "PLAN_JOURNEY"
                    intent.putExtra(EXTRA_CITY, action.cityId).putExtra(EXTRA_FROM, action.fromComplexId).putExtra(EXTRA_TO, action.toComplexId)
                }
                is Station -> {
                    // Unique data keeps one pending intent per station apart.
                    intent.action = ACTION_PREFIX + "STATION"
                    intent.data = android.net.Uri.parse("metropulse://station/${action.cityId}/${action.complexId}")
                    intent.putExtra(EXTRA_CITY, action.cityId).putExtra(EXTRA_STATION, action.complexId)
                }
            }
            return intent
        }

        fun from(intent: Intent?): LaunchAction? {
            val action = intent?.action?.removePrefix(ACTION_PREFIX) ?: return null
            return when (action) {
                "SEARCH" -> Search
                "PLAN" -> Plan
                "ON_BOARD" -> OnBoard
                "START_COMMUTE" -> StartCommute
                "SETTINGS" -> Settings
                "PLAN_JOURNEY" -> {
                    val city = intent.getStringExtra(EXTRA_CITY)
                    val from = intent.getStringExtra(EXTRA_FROM)
                    val to = intent.getStringExtra(EXTRA_TO)
                    if (city != null && from != null && to != null) PlanJourney(city, from, to) else null
                }
                "STATION" -> {
                    val city = intent.getStringExtra(EXTRA_CITY)
                    val station = intent.getStringExtra(EXTRA_STATION)
                    if (city != null && station != null) Station(city, station) else null
                }
                else -> null
            }
        }
    }
}

/** The pending launch action, handed from the activity to the UI and consumed once. */
object LaunchActions {
    private val _pending = MutableStateFlow<LaunchAction?>(null)
    val pending: StateFlow<LaunchAction?> = _pending.asStateFlow()

    fun post(action: LaunchAction) {
        _pending.value = action
    }

    fun consume(action: LaunchAction) {
        _pending.compareAndSet(action, null)
    }
}
