package io.github.arnavdugad.metropulse.ui.lines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.ui.common.composeColor
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.transit.DayType
import io.github.arnavdugad.metropulse.core.domain.transit.Line
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.ui.city.ChooseCity
import io.github.arnavdugad.metropulse.ui.city.CityFailed
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.StatusBarScrim
import io.github.arnavdugad.metropulse.ui.common.hoursText
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import java.time.LocalDate

/** One line as the Lines tab shows it. */
private data class LineRow(
    val line: Line,
    val from: String,
    val to: String,
    val stations: Int,
    /** Today's published hours, if any. */
    val hours: Pair<Int, Int>?,
    val timed: Boolean,
)

private fun rows(network: TransitNetwork, today: LocalDate): List<LineRow> {
    val dayType = network.dayType(today)
    return network.lines.map { line ->
        val patterns = network.patterns.filter { it.lineId == line.id }
        val main = patterns.filter { it.direction == 0 }.maxByOrNull { it.size } ?: patterns.maxBy { it.size }
        val complexes = patterns.flatMap { p -> p.stationIds.map { network.stationById.getValue(it).complexId } }.toSet()
        LineRow(
            line = line,
            from = network.stationById.getValue(main.stationIds.first()).name,
            to = network.stationById.getValue(main.stationIds.last()).name,
            stations = complexes.size,
            hours = network.operatingHours(line.id, today)?.let { it.firstSecs to it.lastSecs },
            timed = network.hasTimetable(line.id, dayType),
        )
    }
}

@Composable
fun LinesScreen(cityViewModel: CityViewModel, onLine: (lineId: String) -> Unit) {
    val state by cityViewModel.state.collectAsStateWithLifecycle()
    val colors = MetroPulseTheme.colors
    when (val s = state) {
        is CityState.Ready -> Box(Modifier.fillMaxSize()) {
            LineList(s.city.network, onLine)
            StatusBarScrim()
        }
        is CityState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = colors.accent)
        }
        is CityState.Failed -> CityFailed(cityViewModel)
        CityState.NoCity -> ChooseCity(cityViewModel)
    }
}

@Composable
private fun LineList(network: TransitNetwork, onLine: (String) -> Unit) {
    val colors = MetroPulseTheme.colors
    val rows = remember(network) { rows(network, LocalDate.now(network.timezone)) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 12.dp)) {
                Text(
                    stringResource(R.string.lines_title),
                    style = MetroPulseTheme.type.display,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Text(network.cityName, style = MetroPulseTheme.type.bodyStrong, color = colors.textSecondary)
            }
        }
        items(rows, key = { it.line.id }) { LineCard(it) { onLine(it.line.id) } }
        item {
            val notes = listOfNotNull(
                stringResource(R.string.lines_note_approximate).takeIf { rows.any { it.timed && it.line.quality == DataQuality.Approximate } },
                stringResource(R.string.lines_note_unavailable).takeIf { rows.any { !it.timed } },
                stringResource(R.string.lines_attribution),
            )
            Text(
                notes.joinToString(" "),
                style = MetroPulseTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun LineCard(row: LineRow, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val lineColor = row.line.composeColor()
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .sharedElement("line-${row.line.id}")
            .shadow(8.dp, MpShapes.card, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(MpShapes.card)
            .background(colors.surface)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(lineColor))
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(lineColor))
                Spacer(Modifier.width(8.dp))
                Text(row.line.name, style = MetroPulseTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (row.timed) {
                    TimeSourceChip(if (row.line.quality == DataQuality.Approximate) TimeSource.Approximate else TimeSource.Scheduled)
                } else {
                    Tag(stringResource(R.string.lines_times_unavailable), tint = colors.textSecondary)
                }
            }
            Text("${row.from}  →  ${row.to}", style = MetroPulseTheme.type.body, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    pluralStringResource(R.plurals.stations_count, row.stations, row.stations),
                    style = MetroPulseTheme.type.label,
                    color = colors.textTertiary,
                )
                row.hours?.let { (first, last) ->
                    Text(
                        hoursText(first, last, clock),
                        style = MetroPulseTheme.type.label,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}
