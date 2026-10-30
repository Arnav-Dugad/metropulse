package io.github.arnavdugad.metropulse.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.user.UserRepository
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.user.TripRecord
import io.github.arnavdugad.metropulse.ui.common.DetailTopBar
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.LineChip
import io.github.arnavdugad.metropulse.ui.common.RoundIconButton
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.ui.common.formatDuration
import io.github.arnavdugad.metropulse.ui.common.formatMoney
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class TripsViewModel @Inject constructor(private val user: UserRepository) : ViewModel() {
    /** Null until read. */
    val trips: StateFlow<List<TripRecord>?> = user.trips.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun clear() {
        viewModelScope.launch { user.clearTrips() }
    }
}

/** Trips finished with on-board mode: totals, then each trip, newest first. Kept on the device only. */
@Composable
fun TripsScreen(network: TransitNetwork?, viewModel: TripsViewModel, onBack: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val list = trips ?: return
    LazyColumn(
        Modifier.fillMaxSize().background(colors.background),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        item {
            DetailTopBar(onBack) {
                Spacer(Modifier.weight(1f))
                if (list.isNotEmpty()) RoundIconButton(MpIcons.Close, stringResource(R.string.trips_clear), { confirmClear = true }, colors.textSecondary)
            }
            Text(
                stringResource(R.string.trips_title),
                style = MetroPulseTheme.type.display,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).semantics { heading() },
            )
        }
        if (list.isEmpty()) {
            item { EmptyState(MpIcons.History, stringResource(R.string.trips_empty_title), stringResource(R.string.trips_empty_body), Modifier.padding(top = 40.dp)) }
            return@LazyColumn
        }
        item { Stats(list, network, Modifier.padding(horizontal = 20.dp)) }
        item { SectionTitle(stringResource(R.string.trips_recent), Modifier.padding(horizontal = 20.dp)) }
        items(list, key = { it.startedAt }) { TripRow(it, network, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
        item {
            Text(
                stringResource(R.string.trips_private),
                style = MetroPulseTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = colors.surface,
            title = { Text(stringResource(R.string.trips_clear_title), color = colors.textPrimary) },
            text = { Text(stringResource(R.string.trips_clear_body), color = colors.textSecondary) },
            confirmButton = { TextButton({ viewModel.clear(); confirmClear = false }) { Text(stringResource(R.string.trips_clear), color = colors.danger) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.update_cancel), color = colors.textSecondary) } },
        )
    }
}

@Composable
private fun Stats(trips: List<TripRecord>, network: TransitNetwork?, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val rideSecs = trips.sumOf { it.rideSecs }
    val km = trips.sumOf { it.distanceM } / 1000.0
    // Money is only added up within one currency; the most used currency is shown.
    val spend = trips.filter { it.fareMinor != null }.groupBy { it.currency }.maxByOrNull { it.value.size }
    val topLine = trips.flatMap { it.lineIds }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    Column(
        modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row {
            Stat(trips.size.toString(), pluralStringResource(R.plurals.trips_count_label, trips.size), Modifier.weight(1f))
            Stat(formatDuration(rideSecs), stringResource(R.string.trips_on_trains), Modifier.weight(1f))
        }
        Row {
            Stat(stringResource(R.string.trips_km, km), stringResource(R.string.trips_travelled), Modifier.weight(1f))
            if (spend != null) Stat(formatMoney(spend.value.sumOf { it.fareMinor!! }, spend.key), stringResource(R.string.trips_fares), Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
        }
        val line = topLine?.let { network?.lineById?.get(it) }
        if (line != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.trips_top_line), style = MetroPulseTheme.type.caption, color = colors.textSecondary)
                Spacer(Modifier.width(8.dp))
                LineChip(line)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    Column(modifier) {
        Text(value, style = MetroPulseTheme.type.headline, color = colors.textPrimary)
        Text(label, style = MetroPulseTheme.type.caption, color = colors.textSecondary)
    }
}

@Composable
private fun TripRow(trip: TripRecord, network: TransitNetwork?, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val zone = remember(network) { network?.timezone ?: ZoneId.systemDefault() }
    val start = remember(trip.startedAt, zone) { Instant.ofEpochMilli(trip.startedAt).atZone(zone) }
    val date = remember(start) { start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }
    Column(
        modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${trip.fromName} → ${trip.toName}", style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f))
            if (!trip.arrived) Text(stringResource(R.string.trips_ended_early), style = MetroPulseTheme.type.caption, color = colors.textTertiary)
        }
        Text(
            listOfNotNull(
                "$date · ${clock(start.toLocalTime().toSecondOfDay())}",
                formatDuration(trip.rideSecs),
                trip.fareMinor?.let { formatMoney(it, trip.currency) },
            ).joinToString(" · "),
            style = MetroPulseTheme.type.caption,
            color = colors.textSecondary,
        )
        val lines = trip.lineIds.mapNotNull { id -> network?.lineById?.get(id)?.takeIf { network.cityId == trip.cityId } }
        if (lines.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { lines.forEach { LineChip(it) } }
    }
}
