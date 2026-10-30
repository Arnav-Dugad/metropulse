package io.github.arnavdugad.metropulse.ui.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import io.github.arnavdugad.metropulse.core.data.settings.Settings
import io.github.arnavdugad.metropulse.core.data.settings.ThemeMode
import io.github.arnavdugad.metropulse.launch.CommuteTileService
import io.github.arnavdugad.metropulse.ui.common.ChoiceChip
import io.github.arnavdugad.metropulse.ui.common.StationPickerSheet
import io.github.arnavdugad.metropulse.ui.onboarding.canUseFullScreen
import io.github.arnavdugad.metropulse.ui.onboarding.openFullScreenSettings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.LaunchedEffect
import io.github.arnavdugad.metropulse.core.designsystem.component.TextAction
import io.github.arnavdugad.metropulse.core.map.OfflineMapState
import io.github.arnavdugad.metropulse.core.map.OfflineMaps
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.update.UpdateState
import io.github.arnavdugad.metropulse.core.designsystem.component.pressScale
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import io.github.arnavdugad.metropulse.ui.common.StatusBarScrim
import io.github.arnavdugad.metropulse.update.UpdateViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val REPO_URL = "https://github.com/Arnav-Dugad/metropulse"

@Composable
fun SettingsScreen(updateViewModel: UpdateViewModel, cityViewModel: CityViewModel, settingsViewModel: SettingsViewModel, onAbout: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val context = LocalContext.current
    val cityState by cityViewModel.state.collectAsStateWithLifecycle()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    val prefs = settings ?: Settings()
    val commute by settingsViewModel.commuteStations.collectAsStateWithLifecycle()
    val dataCheck by settingsViewModel.dataCheck.collectAsStateWithLifecycle()
    val city = (cityState as? CityState.Ready)?.city
    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) } // true: home, false: work
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val autoCheck by updateViewModel.autoCheck.collectAsStateWithLifecycle()
    val lastChecked by updateViewModel.lastCheckedAt.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 120.dp),
        ) {
            Text(
                stringResource(R.string.settings_title),
                style = MetroPulseTheme.type.display,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.size(24.dp))

            Section(stringResource(R.string.settings_section_city)) {
                val ready = cityState as? CityState.Ready
                ActionRow(
                    icon = MpIcons.Pin,
                    title = ready?.city?.network?.cityName ?: stringResource(R.string.city_choose_action),
                    subtitle = ready?.city?.network?.let { stringResource(R.string.settings_city_data, dataVersionLabel(it.dataVersion)) },
                    onClick = cityViewModel::showPicker,
                    trailing = { Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp)) },
                )
            }

            val bbox = city?.info?.bbox
            if (city != null && bbox != null && bbox.size == 4) {
                Spacer(Modifier.size(20.dp))
                Section(stringResource(R.string.settings_section_offline)) {
                    OfflineMapRow(city.network.cityId, city.network.cityName, bbox)
                }
            }

            if (city != null) {
                Spacer(Modifier.size(20.dp))
                Section(stringResource(R.string.settings_section_commute)) {
                    val network = city.network
                    for (home in listOf(true, false)) {
                        val id = if (home) commute.first else commute.second
                        ActionRow(
                            icon = if (home) MpIcons.Pin else MpIcons.Route,
                            title = stringResource(if (home) R.string.settings_commute_home else R.string.settings_commute_work),
                            subtitle = id?.takeIf { network.complexes.containsKey(it) }?.let(network::complexName) ?: stringResource(R.string.settings_commute_not_set),
                            onClick = { picking = home },
                            trailing = { Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp)) },
                        )
                        Divider()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ActionRow(
                            icon = MpIcons.Sparkle,
                            title = stringResource(R.string.settings_commute_tile),
                            subtitle = stringResource(R.string.settings_commute_tile_body),
                            onClick = { requestTile(context) },
                            trailing = { Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp)) },
                        )
                    } else {
                        ValueRow(MpIcons.Sparkle, stringResource(R.string.settings_commute_tile), "", subtitle = stringResource(R.string.settings_commute_tile_body_legacy))
                    }
                }
            }

            Spacer(Modifier.size(20.dp))
            Section(stringResource(R.string.settings_section_trips)) {
                ChoiceRow(MpIcons.Bell, stringResource(R.string.settings_wake_alert), stringResource(R.string.settings_wake_alert_body)) {
                    for (n in 1..2) {
                        ChoiceChip(pluralStringResource(R.plurals.onboard_stops_before, n, n), prefs.alertStops == n, idle = colors.background) {
                            settingsViewModel.setAlertStops(n)
                        }
                    }
                }
                Divider()
                SwitchRow(
                    icon = MpIcons.Clock,
                    title = stringResource(R.string.settings_live),
                    subtitle = stringResource(R.string.settings_live_body),
                    checked = prefs.liveData,
                    onCheckedChange = settingsViewModel::setLiveData,
                )
                if (!context.canUseFullScreen()) {
                    Divider()
                    ActionRow(
                        icon = MpIcons.Bell,
                        title = stringResource(R.string.settings_full_screen),
                        subtitle = stringResource(R.string.settings_full_screen_body),
                        onClick = { context.openFullScreenSettings() },
                        trailing = { Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp)) },
                    )
                }
            }

            Spacer(Modifier.size(20.dp))
            Section(stringResource(R.string.settings_section_appearance)) {
                ChoiceRow(MpIcons.Sparkle, stringResource(R.string.settings_theme), null) {
                    for ((mode, label) in listOf(
                        ThemeMode.System to R.string.settings_theme_system,
                        ThemeMode.Light to R.string.settings_theme_light,
                        ThemeMode.Dark to R.string.settings_theme_dark,
                    )) {
                        ChoiceChip(stringResource(label), prefs.theme == mode, idle = colors.background) { settingsViewModel.setTheme(mode) }
                    }
                }
                Divider()
                SwitchRow(
                    icon = MpIcons.Info,
                    title = stringResource(R.string.settings_high_contrast),
                    subtitle = stringResource(R.string.settings_high_contrast_body),
                    checked = prefs.highContrast,
                    onCheckedChange = settingsViewModel::setHighContrast,
                )
                Divider()
                SwitchRow(
                    icon = MpIcons.Train,
                    title = stringResource(R.string.settings_reduce_motion),
                    subtitle = stringResource(R.string.settings_reduce_motion_body),
                    checked = prefs.reduceMotion,
                    onCheckedChange = settingsViewModel::setReduceMotion,
                )
            }

            if (settingsViewModel.cityDataAvailable) {
                Spacer(Modifier.size(20.dp))
                Section(stringResource(R.string.settings_section_data)) {
                    SwitchRow(
                        icon = MpIcons.Refresh,
                        title = stringResource(R.string.settings_data_updates),
                        subtitle = stringResource(R.string.settings_data_updates_body),
                        checked = prefs.cityDataUpdates,
                        onCheckedChange = settingsViewModel::setCityDataUpdates,
                    )
                    Divider()
                    ActionRow(
                        icon = MpIcons.Download,
                        title = stringResource(R.string.settings_data_check),
                        subtitle = null,
                        onClick = settingsViewModel::checkCityData,
                        trailing = { DataCheckStatus(dataCheck) },
                    )
                }
            }

            Spacer(Modifier.size(20.dp))
            Section(stringResource(R.string.settings_section_updates)) {
                ValueRow(MpIcons.Info, stringResource(R.string.settings_version), updateViewModel.installedVersion)
                Divider()
                if (updateViewModel.updatesEnabled) {
                    SwitchRow(
                        icon = MpIcons.Refresh,
                        title = stringResource(R.string.settings_auto_check),
                        subtitle = stringResource(R.string.settings_auto_check_body),
                        checked = autoCheck,
                        onCheckedChange = updateViewModel::setAutoCheck,
                    )
                    Divider()
                    ActionRow(
                        icon = MpIcons.Download,
                        title = stringResource(R.string.settings_check_now),
                        subtitle = lastChecked?.let {
                            val now = System.currentTimeMillis()
                            val ago = if (now - it < DateUtils.MINUTE_IN_MILLIS) {
                                stringResource(R.string.settings_just_now)
                            } else {
                                DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString().lowercase()
                            }
                            stringResource(R.string.settings_last_checked, ago)
                        },
                        onClick = {
                            if (updateState is UpdateState.Available || updateState is UpdateState.Downloading || updateState is UpdateState.ReadyToInstall) {
                                updateViewModel.openFromNotification()
                            } else {
                                updateViewModel.checkNow()
                            }
                        },
                        trailing = { UpdateStatus(updateState) },
                    )
                } else {
                    ValueRow(MpIcons.Download, stringResource(R.string.settings_updates_disabled), "")
                }
            }

            Spacer(Modifier.size(20.dp))
            Section(stringResource(R.string.settings_section_about)) {
                ActionRow(
                    icon = MpIcons.Code,
                    title = stringResource(R.string.settings_source),
                    subtitle = stringResource(R.string.settings_source_body),
                    onClick = { uriHandler.openUri(REPO_URL) },
                    trailing = { Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp)) },
                )
                Divider()
                ValueRow(MpIcons.Shield, stringResource(R.string.settings_privacy), "", subtitle = stringResource(R.string.settings_privacy_body))
                Divider()
                ActionRow(
                    icon = MpIcons.Info,
                    title = stringResource(R.string.settings_licences),
                    subtitle = stringResource(R.string.settings_licences_body),
                    onClick = onAbout,
                    trailing = { Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp)) },
                )
            }
        }
        StatusBarScrim()
    }
    val home = picking
    if (home != null && city != null) {
        StationPickerSheet(
            city = city,
            title = stringResource(if (home) R.string.settings_commute_home else R.string.settings_commute_work),
            onPick = { id ->
                settingsViewModel.setCommuteStation(home, id)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

/** Offers the commute tile through Android's own "add tile" prompt (Android 13+). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun requestTile(context: Context) {
    context.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
        ComponentName(context, CommuteTileService::class.java),
        context.getString(R.string.tile_label),
        Icon.createWithResource(context, R.drawable.ic_tile_commute),
        context.mainExecutor,
    ) { }
}

/** Download, progress, or downloaded size with a way to remove it. */
@Composable
private fun OfflineMapRow(cityId: String, cityName: String, bbox: List<Double>) {
    val colors = MetroPulseTheme.colors
    val context = LocalContext.current
    LaunchedEffect(Unit) { OfflineMaps.refresh(context) }
    val states by OfflineMaps.states.collectAsStateWithLifecycle()
    val state = states[cityId] ?: OfflineMapState.None
    val plan = remember(bbox) { OfflineMaps.plan(bbox) }
    val detail = stringResource(if (plan.maxZoom >= 14) R.string.settings_offline_full else R.string.settings_offline_partial)
    ActionRow(
        icon = MpIcons.Offline,
        title = when (state) {
            is OfflineMapState.Ready -> stringResource(R.string.settings_offline_ready, cityName)
            is OfflineMapState.Downloading -> stringResource(R.string.settings_offline_downloading, (state.fraction * 100).toInt())
            else -> stringResource(R.string.settings_offline_download, cityName)
        },
        subtitle = when (state) {
            is OfflineMapState.Ready -> Formatter.formatShortFileSize(context, state.bytes) + " · " + detail
            is OfflineMapState.Downloading -> Formatter.formatShortFileSize(context, state.bytes)
            OfflineMapState.Failed -> stringResource(R.string.settings_offline_failed)
            OfflineMapState.None -> detail + " · " + stringResource(R.string.settings_offline_wifi)
        },
        onClick = {
            when (state) {
                OfflineMapState.None, OfflineMapState.Failed -> OfflineMaps.download(context, cityId, bbox)
                else -> Unit
            }
        },
        trailing = {
            when (state) {
                is OfflineMapState.Downloading -> TextAction(stringResource(R.string.action_cancel), { OfflineMaps.cancel(cityId) })
                is OfflineMapState.Ready -> TextAction(stringResource(R.string.action_delete), { OfflineMaps.delete(cityId) }, color = colors.danger)
                else -> Icon(MpIcons.Download, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            }
        },
    )
}

@Composable
private fun DataCheckStatus(state: DataCheck) {
    val colors = MetroPulseTheme.colors
    AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "dataCheck") { s ->
        when (s) {
            DataCheck.Checking -> CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
            DataCheck.UpToDate -> StatusText(stringResource(R.string.settings_up_to_date), colors.live)
            is DataCheck.Updated -> StatusText(pluralStringResource(R.plurals.settings_data_updated, s.count, s.count), colors.accent)
            DataCheck.Failed -> StatusText(stringResource(R.string.settings_check_failed), colors.danger)
            DataCheck.Idle -> Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** A setting chosen from a few chips, shown under its title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(icon: ImageVector, title: String, subtitle: String?, chips: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp)) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            RowText(title, subtitle, Modifier)
            Spacer(Modifier.size(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        }
    }
}

/** Data versions are yyyyMMddHHmm build stamps; show them as a date. */
private fun dataVersionLabel(version: Long): String = runCatching {
    LocalDateTime.parse(version.toString(), DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
}.getOrDefault(version.toString())

@Composable
private fun UpdateStatus(state: UpdateState) {
    val colors = MetroPulseTheme.colors
    AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "status") { s ->
        when (s) {
            UpdateState.Checking -> CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, strokeWidth = 2.dp)
            is UpdateState.UpToDate -> StatusText(stringResource(R.string.settings_up_to_date), colors.live)
            is UpdateState.Available -> StatusText(stringResource(R.string.settings_available, s.release.version.toString()), colors.accent)
            is UpdateState.Downloading -> StatusText(stringResource(R.string.settings_downloading, "${((s.fraction ?: 0f) * 100).toInt()}%"), colors.accent)
            is UpdateState.ReadyToInstall, is UpdateState.Installing -> StatusText(stringResource(R.string.settings_ready), colors.accent)
            is UpdateState.Failed -> StatusText(
                stringResource(if (s.release == null) R.string.settings_check_failed else R.string.settings_update_failed),
                colors.danger,
            )
            else -> Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun StatusText(text: String, color: Color) {
    Text(
        text,
        style = MetroPulseTheme.type.label,
        color = color,
        modifier = Modifier
            .clip(MpShapes.pill)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = MetroPulseTheme.colors
    Text(
        title.uppercase(),
        style = MetroPulseTheme.type.overline,
        color = colors.textTertiary,
        modifier = Modifier.padding(start = 8.dp, bottom = 10.dp).semantics { heading() },
    )
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, MpShapes.card, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(MpShapes.card)
            .background(colors.surface),
        content = content,
    )
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 64.dp), color = MetroPulseTheme.colors.hairline)

@Composable
private fun RowIcon(icon: ImageVector) {
    val colors = MetroPulseTheme.colors
    Box(
        Modifier.size(36.dp).clip(MpShapes.control).background(colors.accentSoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun RowText(title: String, subtitle: String?, modifier: Modifier) {
    Column(modifier) {
        Text(title, style = MetroPulseTheme.type.bodyStrong, color = MetroPulseTheme.colors.textPrimary)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MetroPulseTheme.type.caption, color = MetroPulseTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        RowText(title, subtitle, Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, style = MetroPulseTheme.type.label, color = MetroPulseTheme.colors.textSecondary)
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberMpHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .clickable(interaction, indication = null, role = Role.Button) { haptics.tick(); onClick() }
            .padding(14.dp)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        RowText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}


@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { haptics.tick(); onCheckedChange(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        RowText(title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = colors.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = colors.hairline,
                uncheckedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White,
            ),
        )
    }
}
