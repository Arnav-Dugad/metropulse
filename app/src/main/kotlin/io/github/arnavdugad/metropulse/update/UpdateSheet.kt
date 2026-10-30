package io.github.arnavdugad.metropulse.update

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.update.UpdateState
import io.github.arnavdugad.metropulse.core.data.update.release
import io.github.arnavdugad.metropulse.core.designsystem.component.ProgressPillButton
import io.github.arnavdugad.metropulse.core.designsystem.component.SecondaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TextAction
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.update.AppRelease
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheetHost(viewModel: UpdateViewModel) {
    val visible by viewModel.sheetVisible.collectAsStateWithLifecycle()
    if (!visible) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val awaitingPermission by viewModel.awaitingInstallPermission.collectAsStateWithLifecycle()
    val colors = MetroPulseTheme.colors

    ModalBottomSheet(
        onDismissRequest = viewModel::dismissSheet,
        // Opens at full content height so the primary action is never below the fold.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MpShapes.sheet,
        containerColor = colors.surface,
        scrimColor = Color.Black.copy(alpha = if (colors.isDark) 0.6f else 0.38f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 5.dp)
                    .clip(MpShapes.pill)
                    .background(colors.textTertiary.copy(alpha = 0.35f)),
            )
        },
    ) {
        UpdateSheetContent(
            state = state,
            installedVersion = viewModel.installedVersion,
            awaitingPermission = awaitingPermission,
            onDownload = viewModel::download,
            onCancelDownload = viewModel::cancelDownload,
            onInstall = viewModel::install,
            onRetry = viewModel::retry,
            onSkip = viewModel::skipVersion,
            onDismiss = viewModel::dismissSheet,
        )
    }
}

@Composable
private fun UpdateSheetContent(
    state: UpdateState,
    installedVersion: String,
    awaitingPermission: Boolean,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MetroPulseTheme.colors
    val type = MetroPulseTheme.type
    val haptics = rememberMpHaptics()
    val release = state.release

    LaunchedEffect(state::class) {
        when (state) {
            is UpdateState.ReadyToInstall -> haptics.confirm()
            is UpdateState.Failed -> haptics.reject()
            else -> Unit
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 12.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroBadge(state)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    stringResource(overlineFor(state)).uppercase(Locale.ENGLISH),
                    style = type.overline,
                    color = if (state is UpdateState.Failed) colors.danger else colors.accent,
                )
                Spacer(Modifier.size(4.dp))
                VersionTransition(installedVersion, release?.version?.toString())
            }
        }

        if (release != null) {
            Spacer(Modifier.size(16.dp))
            ReleaseFacts(release, verified = state is UpdateState.ReadyToInstall || state is UpdateState.Installing)
        }

        if (release != null && release.notes.isNotBlank()) {
            Spacer(Modifier.size(20.dp))
            Text(
                stringResource(R.string.update_whats_new),
                style = type.title,
                color = colors.textPrimary,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.size(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .clip(MpShapes.inner)
                    .background(colors.background)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                ReleaseNotesView(release.notes)
            }
        }

        AnimatedVisibility(awaitingPermission, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            PermissionPanel()
        }
        AnimatedVisibility(state is UpdateState.Failed, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            (state as? UpdateState.Failed)?.let { ErrorPanel(it.message) }
        }

        Spacer(Modifier.size(24.dp))
        PrimaryAction(state, onDownload, onInstall, onRetry, onDismiss)
        Spacer(Modifier.size(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            when (state) {
                is UpdateState.Available -> {
                    if (!state.skipped) TextAction(stringResource(R.string.update_skip), onSkip)
                    TextAction(stringResource(R.string.update_later), onDismiss)
                }
                is UpdateState.Downloading -> {
                    TextAction(stringResource(R.string.update_cancel), onCancelDownload)
                    TextAction(stringResource(R.string.update_hide), onDismiss)
                }
                is UpdateState.ReadyToInstall, is UpdateState.Failed -> TextAction(stringResource(R.string.update_later), onDismiss)
                else -> Unit
            }
        }
    }
}

@Composable
private fun HeroBadge(state: UpdateState) {
    val colors = MetroPulseTheme.colors
    val icon: ImageVector = when (state) {
        is UpdateState.ReadyToInstall -> MpIcons.Check
        is UpdateState.Failed -> MpIcons.Info
        is UpdateState.UpToDate -> MpIcons.Shield
        else -> MpIcons.Sparkle
    }
    // Pops in on a spring when the sheet first appears.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.6f, MetroPulseTheme.motion.bouncy(), label = "badge")
    Box(
        Modifier
            .size(64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(MpShapes.inner)
            .background(
                if (state is UpdateState.Failed) {
                    Brush.linearGradient(listOf(colors.danger.copy(alpha = 0.85f), colors.danger))
                } else {
                    colors.tripBrush
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun VersionTransition(installed: String, latest: String?) {
    val colors = MetroPulseTheme.colors
    val type = MetroPulseTheme.type
    if (latest == null) {
        Text("MetroPulse $installed", style = type.headline, color = colors.textPrimary)
        return
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(if (shown) 1f else 0f, MetroPulseTheme.motion.bouncy(), label = "version")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(installed, style = type.title, color = colors.textTertiary)
        Icon(
            MpIcons.ArrowRight, contentDescription = stringResource(R.string.update_to),
            tint = colors.textTertiary,
            modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
        )
        Text(
            latest,
            style = type.headline,
            color = colors.textPrimary,
            modifier = Modifier.graphicsLayer {
                alpha = progress.coerceIn(0f, 1f)
                translationX = (1f - progress) * 40.dp.toPx()
            },
        )
    }
}

@Composable
private fun ReleaseFacts(release: AppRelease, verified: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (release.apk.sizeBytes > 0) {
            Tag(String.format(Locale.ENGLISH, "%.1f MB", release.apk.sizeBytes / 1_048_576.0), icon = MpIcons.Download)
        }
        release.publishedAtEpochMillis?.let {
            val date = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
            Tag(date, icon = MpIcons.Clock, tint = MetroPulseTheme.colors.textSecondary)
        }
        // Only claim "Verified" once the checksum and signing certificate have actually been checked.
        when {
            verified -> Tag(stringResource(R.string.update_verified), icon = MpIcons.Shield, tint = MetroPulseTheme.colors.live)
            release.apk.sha256 != null || release.apk.checksumUrl != null ->
                Tag(stringResource(R.string.update_checksum), icon = MpIcons.Shield, tint = MetroPulseTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun PermissionPanel() {
    val colors = MetroPulseTheme.colors
    val context = LocalContext.current
    Column(
        Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clip(MpShapes.inner)
            .background(colors.accentSoft)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MpIcons.Shield, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.update_permission_title), style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary)
        }
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.update_permission_body), style = MetroPulseTheme.type.body, color = colors.textSecondary)
        Spacer(Modifier.size(14.dp))
        SecondaryButton(
            text = stringResource(R.string.update_permission_action),
            icon = MpIcons.Sliders,
            container = colors.surface,
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
                )
            },
        )
    }
}

@Composable
private fun ErrorPanel(message: String) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier
            .padding(top = 20.dp)
            .fillMaxWidth()
            .clip(MpShapes.inner)
            .background(colors.danger.copy(alpha = 0.1f))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MpIcons.Info, null, tint = colors.danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(message, style = MetroPulseTheme.type.body, color = colors.textPrimary)
    }
}

@Composable
private fun PrimaryAction(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val downloading = state as? UpdateState.Downloading
    val percent = downloading?.fraction?.let { "${(it * 100).toInt()}%" }.orEmpty()
    val (label, icon, action) = when (state) {
        is UpdateState.Available -> Triple(stringResource(R.string.update_now), MpIcons.Download, onDownload)
        is UpdateState.ReadyToInstall -> Triple(stringResource(R.string.update_install), MpIcons.Check, onInstall)
        is UpdateState.Failed -> Triple(stringResource(R.string.update_retry), MpIcons.Refresh, onRetry)
        else -> Triple(stringResource(R.string.update_done), MpIcons.Check, onDismiss)
    }
    ProgressPillButton(
        text = label,
        icon = icon,
        progress = downloading?.fraction,
        busy = state is UpdateState.Downloading || state is UpdateState.Installing || state is UpdateState.Checking,
        busyLabel = percent,
        onClick = action,
    )
}

private fun overlineFor(state: UpdateState): Int = when (state) {
    is UpdateState.Downloading -> R.string.update_overline_downloading
    is UpdateState.ReadyToInstall -> R.string.update_overline_ready
    is UpdateState.Installing -> R.string.update_overline_installing
    is UpdateState.Failed -> R.string.update_overline_failed
    is UpdateState.UpToDate -> R.string.update_overline_current
    is UpdateState.Checking -> R.string.update_overline_checking
    else -> R.string.update_overline_available
}
