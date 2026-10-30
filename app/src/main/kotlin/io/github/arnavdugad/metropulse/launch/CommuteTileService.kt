package io.github.arnavdugad.metropulse.launch

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.onboard.OnboardController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Start my commute": opens the app and starts on-board mode for the saved commute. The trip is
 * started by the visible activity, which Android requires for a location foreground service.
 */
@AndroidEntryPoint
class CommuteTileService : TileService() {

    @Inject lateinit var commute: CommuteStarter
    @Inject lateinit var controller: OnboardController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { refresh() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val action = when {
                controller.session.value != null -> null
                commute.describe() == null -> LaunchAction.Settings
                else -> LaunchAction.StartCommute
            }
            unlockAndRun { open(action) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun refresh() {
        val tile = qsTile ?: return
        val running = controller.session.value
        val leg = if (running == null) commute.describe() else null
        tile.label = getString(R.string.tile_label)
        tile.state = if (running != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                running != null -> getString(R.string.tile_running, running.toName)
                leg != null -> getString(R.string.tile_leg, leg.second)
                else -> getString(R.string.tile_not_set)
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(action: LaunchAction?) {
        val intent = if (action != null) LaunchAction.intent(this, action) else packageManager.getLaunchIntentForPackage(packageName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** Asks the system to refresh the tile (after a trip starts or ends, or the commute changes). */
        fun requestRefresh(context: Context) {
            requestListeningState(context, ComponentName(context, CommuteTileService::class.java))
        }
    }
}
