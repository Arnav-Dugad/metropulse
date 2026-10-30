package io.github.arnavdugad.metropulse.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.PrimaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.SecondaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.TextAction
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.ui.common.NetworkScene
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.ui.city.CityList
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import kotlinx.coroutines.launch

private const val PAGES = 4

/** First run: what the app does, the city, and the two permissions on-board mode needs, each explained. */
@Composable
fun OnboardingScreen(cityViewModel: CityViewModel, onDone: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val cityState by cityViewModel.state.collectAsStateWithLifecycle()
    val cityChosen = cityState !is CityState.NoCity && !(cityState is CityState.Loading && (cityState as CityState.Loading).cityId == null)
    fun next() = scope.launch { if (pager.currentPage < PAGES - 1) pager.animateScrollToPage(pager.currentPage + 1) else onDone() }
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .background(Brush.verticalGradient(listOf(colors.accentSoft.copy(alpha = if (colors.isDark) 0.45f else 0.8f), colors.background), endY = 1400f)),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Swiping ahead is allowed only once the city is chosen; buttons carry the flow.
            HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = cityChosen || pager.currentPage == 0) { page ->
                when (page) {
                    0 -> WelcomePage(onNext = ::next)
                    1 -> CityPage(cityViewModel, cityChosen, onNext = ::next)
                    2 -> LocationPage(onNext = ::next)
                    else -> NotificationsPage(onDone = onDone)
                }
            }
            PageDots(pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun WelcomePage(onNext: () -> Unit) {
    Page(MpIcons.Train, stringResource(R.string.welcome_title), stringResource(R.string.welcome_body), scene = true) {
        Feature(MpIcons.Offline, stringResource(R.string.welcome_offline))
        Feature(MpIcons.Bell, stringResource(R.string.welcome_alerts))
        Feature(MpIcons.Clock, stringResource(R.string.welcome_honest))
        Feature(MpIcons.Shield, stringResource(R.string.welcome_private))
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.welcome_start), onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CityPage(cityViewModel: CityViewModel, cityChosen: Boolean, onNext: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val state by cityViewModel.state.collectAsStateWithLifecycle()
    val current = when (val s = state) {
        is CityState.Ready -> s.city.network.cityId
        is CityState.Loading -> s.cityId
        is CityState.Failed -> s.cityId
        CityState.NoCity -> null
    }
    Column(Modifier.fillMaxSize().padding(top = 24.dp)) {
        Text(
            stringResource(R.string.welcome_city_title),
            style = MetroPulseTheme.type.display,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.welcome_city_body),
            style = MetroPulseTheme.type.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        CityList(
            cities = cityViewModel.cities,
            currentId = current,
            onSelect = cityViewModel::select,
            modifier = Modifier.weight(1f),
            showTitle = false,
            listModifier = Modifier.weight(1f),
        )
        PrimaryButton(
            stringResource(R.string.welcome_continue),
            onNext,
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            enabled = cityChosen,
        )
    }
}

@Composable
private fun LocationPage(onNext: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasLocation()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = context.hasLocation() }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = result.values.any { it }
        onNext()
    }
    Page(MpIcons.Locate, stringResource(R.string.welcome_location_title), stringResource(R.string.welcome_location_body)) {
        Feature(MpIcons.Shield, stringResource(R.string.welcome_location_private))
        Feature(MpIcons.Clock, stringResource(R.string.welcome_location_fallback))
        Spacer(Modifier.weight(1f))
        if (granted) {
            Granted(stringResource(R.string.welcome_location_granted))
            PrimaryButton(stringResource(R.string.welcome_continue), onNext, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(
                stringResource(R.string.welcome_location_allow),
                { request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                Modifier.fillMaxWidth(),
            )
            TextAction(stringResource(R.string.welcome_not_now), onNext, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun NotificationsPage(onDone: () -> Unit) {
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(context.hasNotifications()) }
    var fullScreen by remember { mutableStateOf(context.canUseFullScreen()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notifications = context.hasNotifications()
        fullScreen = context.canUseFullScreen()
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    Page(MpIcons.Bell, stringResource(R.string.welcome_alerts_title), stringResource(R.string.welcome_alerts_body)) {
        Feature(MpIcons.Train, stringResource(R.string.welcome_alerts_progress))
        Feature(MpIcons.Bell, stringResource(R.string.welcome_alerts_wake))
        Spacer(Modifier.weight(1f))
        when {
            !notifications -> {
                PrimaryButton(
                    stringResource(R.string.welcome_alerts_allow),
                    { if (Build.VERSION.SDK_INT >= 33) request.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    Modifier.fillMaxWidth(),
                )
                TextAction(stringResource(R.string.welcome_not_now), onDone, Modifier.align(Alignment.CenterHorizontally))
            }
            !fullScreen -> {
                Granted(stringResource(R.string.welcome_alerts_granted))
                SecondaryButton(
                    stringResource(R.string.welcome_full_screen_allow),
                    { context.openFullScreenSettings() },
                    Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                PrimaryButton(stringResource(R.string.welcome_finish), onDone, Modifier.fillMaxWidth())
            }
            else -> {
                Granted(stringResource(R.string.welcome_alerts_granted))
                PrimaryButton(stringResource(R.string.welcome_finish), onDone, Modifier.fillMaxWidth())
            }
        }
    }
}

/** An animated emblem, a title and a body, then the page's own content. */
@Composable
private fun Page(icon: ImageVector, title: String, body: String, scene: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MetroPulseTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 40.dp, bottom = 8.dp),
    ) {
        if (scene) NetworkScene(Modifier.fillMaxWidth().height(170.dp)) else Emblem(icon)
        Spacer(Modifier.height(32.dp))
        Text(title, style = MetroPulseTheme.type.display, color = colors.textPrimary, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(10.dp))
        Text(body, style = MetroPulseTheme.type.body, color = colors.textSecondary)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            content()
        }
    }
}

/** A gradient disc with the page's glyph and slowly breathing rings (still when motion is reduced). */
@Composable
private fun Emblem(icon: ImageVector) {
    val colors = MetroPulseTheme.colors
    val reduced = MetroPulseTheme.motion.reduced
    val pulse = rememberInfiniteTransition(label = "emblem")
    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Restart), label = "ring")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        for (i in 0..1) {
            val phase = if (reduced) 0.35f + i * 0.3f else (t + i * 0.5f) % 1f
            Box(
                Modifier
                    .size(112.dp)
                    .scale(0.6f + 0.4f * phase)
                    .graphicsLayer { alpha = (1f - phase) * 0.55f }
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.35f)),
            )
        }
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(colors.tripBrush),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    val colors = MetroPulseTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(MpShapes.control).background(colors.surface), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MetroPulseTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Granted(text: String) {
    val colors = MetroPulseTheme.colors
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(MpIcons.Check, null, tint = colors.live, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MetroPulseTheme.type.bodyStrong, color = colors.live)
    }
}

@Composable
private fun PageDots(current: Int, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(PAGES) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 8.dp, MetroPulseTheme.motion.press(), label = "dot")
            val colour by animateColorAsState(if (i == current) colors.accent else colors.textTertiary.copy(alpha = 0.4f), label = "dotColour")
            Box(Modifier.height(8.dp).width(width).clip(MpShapes.pill).background(colour))
        }
    }
}

private fun Context.hasLocation() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun Context.hasNotifications() =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/** Android 14+ may withhold full-screen alerts from apps that are not alarm clocks or phones. */
internal fun Context.canUseFullScreen() =
    Build.VERSION.SDK_INT < 34 || getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

internal fun Context.openFullScreenSettings() {
    if (Build.VERSION.SDK_INT >= 34) {
        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
    }
}
