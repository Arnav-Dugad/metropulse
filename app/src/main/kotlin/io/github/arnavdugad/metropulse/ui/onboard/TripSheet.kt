package io.github.arnavdugad.metropulse.ui.onboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.ui.common.composeColor
import io.github.arnavdugad.metropulse.core.designsystem.component.PrimaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import io.github.arnavdugad.metropulse.core.domain.routing.Journey
import io.github.arnavdugad.metropulse.ui.common.ChoiceChip
import io.github.arnavdugad.metropulse.ui.common.SearchField
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripSheetHost(viewModel: OnboardViewModel, city: LoadedCity?) {
    val visible by viewModel.sheetVisible.collectAsStateWithLifecycle()
    if (!visible || city == null) return
    val colors = MetroPulseTheme.colors
    ModalBottomSheet(
        onDismissRequest = viewModel::dismissSheet,
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
        TripSheetContent(viewModel, city)
    }
}

private enum class Editing { None, From, To }

@Composable
private fun TripSheetContent(viewModel: OnboardViewModel, city: LoadedCity) {
    val colors = MetroPulseTheme.colors
    val network = city.network
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    // "From" follows the phone's location until the rider picks a station themselves.
    var fromIsNearest by rememberSaveable { mutableStateOf(true) }
    var to by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(Editing.To) }
    var alertStops by rememberSaveable { mutableIntStateOf(viewModel.defaultAlertStops.value) }
    var noRoute by remember { mutableStateOf(false) }

    LaunchedEffect(network) { viewModel.findNearby(network) }
    LaunchedEffect(nearby) { if (fromIsNearest && nearby != null) from = nearby?.complexId }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Whatever was granted, start: without location the trip follows the timetable.
        val f = from
        val t = to
        if (f != null && t != null) noRoute = !viewModel.start(f, t, alertStops)
    }

    Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
        Text(
            stringResource(R.string.onboard_sheet_title),
            style = MetroPulseTheme.type.headline,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp).semantics { heading() },
        )
        StopRow(
            icon = MpIcons.Locate,
            label = stringResource(R.string.onboard_from),
            value = from?.let(network::complexName) ?: stringResource(R.string.onboard_choose_station),
            detail = if (from != null && from == nearby?.complexId) stringResource(R.string.onboard_nearest, nearby!!.distanceM.toInt()) else null,
            active = editing == Editing.From,
            onClick = { editing = Editing.From },
        )
        Spacer(Modifier.height(8.dp))
        StopRow(
            icon = MpIcons.Pin,
            label = stringResource(R.string.onboard_to),
            value = to?.let(network::complexName) ?: stringResource(R.string.onboard_choose_destination),
            detail = null,
            active = editing == Editing.To,
            onClick = { editing = Editing.To },
        )
        Spacer(Modifier.height(12.dp))

        if (editing != Editing.None) {
            StationPicker(city) { picked ->
                if (editing == Editing.From) {
                    from = picked
                    fromIsNearest = false
                } else {
                    to = picked
                }
                editing = when {
                    from == null -> Editing.From
                    to == null -> Editing.To
                    else -> Editing.None
                }
                noRoute = false
            }
        } else {
            val f = from
            val t = to
            val journey = remember(f, t) { if (f != null && t != null) viewModel.preview(city, f, t) else null }
            if (journey != null) JourneySummary(city, journey) else if (f != null && t != null) {
                Text(stringResource(R.string.onboard_no_route), style = MetroPulseTheme.type.body, color = colors.danger)
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.onboard_wake_me), style = MetroPulseTheme.type.overline, color = colors.textTertiary, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 1..2) {
                    ChoiceChip(pluralStringResource(R.plurals.onboard_stops_before, n, n), selected = alertStops == n, idle = colors.background) { alertStops = n }
                }
            }
            if (noRoute) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.onboard_no_route), style = MetroPulseTheme.type.caption, color = colors.danger)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton(
                text = stringResource(R.string.onboard_start),
                icon = MpIcons.Train,
                enabled = journey != null,
                onClick = {
                    val wanted = buildList {
                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permissions.launch(wanted.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.onboard_privacy),
                style = MetroPulseTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 10.dp, bottom = 16.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun StopRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, detail: String?, active: Boolean, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MpShapes.card)
            .background(if (active) colors.accentSoft else colors.background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MetroPulseTheme.type.caption, color = colors.textSecondary)
            Text(value, style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary)
        }
        if (detail != null) Text(detail, style = MetroPulseTheme.type.caption, color = colors.textTertiary)
    }
}

@Composable
private fun StationPicker(city: LoadedCity, onPick: (String) -> Unit) {
    val colors = MetroPulseTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(query, city) { city.search.search(query, 30) }
    SearchField(query, { query = it }, stringResource(R.string.onboard_search_hint), autoFocus = true)
    LazyColumn(Modifier.heightIn(max = 320.dp).padding(top = 6.dp, bottom = 12.dp)) {
        items(hits, key = { it.complexId }) { hit ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MpShapes.control)
                    .clickable(role = Role.Button) { onPick(hit.complexId) }
                    .padding(horizontal = 8.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(hit.name, style = MetroPulseTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                for (line in city.network.linesAtComplex[hit.complexId].orEmpty()) {
                    Box(Modifier.padding(start = 4.dp).size(10.dp).clip(CircleShape).background(line.composeColor()))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JourneySummary(city: LoadedCity, journey: Journey) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    Column(
        Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.background).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            journey.rides.forEachIndexed { i, ride ->
                val line = city.network.lineById[ride.lineId] ?: return@forEachIndexed
                if (i > 0) Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                val lineColor = line.composeColor()
                Text(
                    line.name,
                    style = MetroPulseTheme.type.label,
                    color = colors.textPrimary,
                    modifier = Modifier.clip(MpShapes.pill).background(lineColor.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
        val minutes = (journey.durationSecs + 30) / 60
        val changes = if (journey.transfers == 0) stringResource(R.string.onboard_direct) else pluralStringResource(R.plurals.onboard_changes, journey.transfers, journey.transfers)
        Text(
            if (journey.timed) {
                stringResource(R.string.onboard_summary_timed, clock(journey.departSecs!!), clock(journey.arriveSecs!!), minutes, changes)
            } else {
                stringResource(R.string.onboard_summary_untimed, minutes, changes)
            },
            style = MetroPulseTheme.type.body,
            color = colors.textSecondary,
        )
    }
}

