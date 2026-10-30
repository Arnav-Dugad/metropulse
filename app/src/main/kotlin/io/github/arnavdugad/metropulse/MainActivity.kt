package io.github.arnavdugad.metropulse

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.core.data.settings.Settings
import io.github.arnavdugad.metropulse.core.data.settings.ThemeMode
import io.github.arnavdugad.metropulse.launch.LaunchAction
import io.github.arnavdugad.metropulse.launch.LaunchActions
import io.github.arnavdugad.metropulse.ui.settings.SettingsViewModel
import android.view.View
import android.view.animation.PathInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.animation.doOnEnd
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.arnavdugad.metropulse.core.data.update.InstallEvent
import io.github.arnavdugad.metropulse.core.data.update.InstallEvents
import io.github.arnavdugad.metropulse.core.data.update.UpdateNotifier
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.ui.MetroPulseRoot
import io.github.arnavdugad.metropulse.core.map.MapFocus
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import io.github.arnavdugad.metropulse.update.UpdateViewModel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()
    private val cityViewModel: CityViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Settings decide the theme and whether to show onboarding; reading them takes milliseconds.
        splash.setKeepOnScreenCondition { settingsViewModel.state.value == null }
        splash.setOnExitAnimationListener { provider ->
            // The mark lifts toward the viewer while the splash dissolves into the app.
            val icon: View = provider.iconView
            val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.35f),
                    ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.35f),
                    ObjectAnimator.ofFloat(provider.view, View.ALPHA, 1f, 0f),
                )
                duration = 360
                interpolator = ease
                doOnEnd { provider.remove() }
                start()
            }
        }
        enableEdgeToEdge()

        handleIntent(intent)
        if (savedInstanceState == null) updateViewModel.onAppStart()

        // PackageInstaller asks for confirmation through an intent that must start from a visible activity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallEvents.latest.filterIsInstance<InstallEvent.NeedsConfirmation>().collect { event ->
                    InstallEvents.consume(event)
                    try {
                        startActivity(event.confirmIntent)
                    } catch (_: ActivityNotFoundException) {
                        // Nothing can confirm installs on this device; the sheet keeps showing "Install".
                    }
                }
            }
        }

        setContent {
            val settings by settingsViewModel.state.collectAsStateWithLifecycle()
            val prefs = settings ?: Settings()
            val dark = when (prefs.theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            MetroPulseTheme(darkTheme = dark, highContrast = prefs.highContrast, reducedMotion = prefs.reduceMotion || animationsOff()) {
                MetroPulseRoot(updateViewModel, settingsViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateViewModel.onResumed()
    }

    /** The system's "Remove animations" (animator duration scale 0) also reduces motion here. */
    private fun animationsOff(): Boolean =
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private fun handleIntent(intent: Intent?) {
        LaunchAction.from(intent)?.let { action ->
            LaunchActions.post(action)
            intent?.action = null
        }
        if (intent?.getBooleanExtra(UpdateNotifier.EXTRA_SHOW_UPDATE, false) == true) {
            intent.removeExtra(UpdateNotifier.EXTRA_SHOW_UPDATE)
            updateViewModel.openFromNotification()
        }
        // Debug builds only: `adb shell am start ... --es debug_camera "lat,lon,zoom,tilt,bearing"`
        // flies the map to an exact view, for checking 3D details by hand.
        if (BuildConfig.DEBUG) {
            intent?.getStringExtra(EXTRA_DEBUG_CAMERA)?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
                ?.takeIf { it.size == 5 }
                ?.let { (lat, lon, zoom, tilt, bearing) -> cityViewModel.focusOn(MapFocus(lat, lon, zoom, tilt, bearing)) }
            intent?.removeExtra(EXTRA_DEBUG_CAMERA)
        }
    }

    private companion object {
        const val EXTRA_DEBUG_CAMERA = "debug_camera"
        // androidx.activity's default navigation bar scrims for three-button navigation.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
