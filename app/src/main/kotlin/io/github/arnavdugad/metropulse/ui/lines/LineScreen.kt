package io.github.arnavdugad.metropulse.ui.lines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.transit.DayType
import io.github.arnavdugad.metropulse.core.domain.transit.Pattern
import io.github.arnavdugad.metropulse.core.domain.transit.ScheduledTrains
import io.github.arnavdugad.metropulse.core.domain.live.LivePositions
import io.github.arnavdugad.metropulse.ui.common.LiveViewModel
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.ui.common.DetailTopBar
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.LineDot
import io.github.arnavdugad.metropulse.ui.common.composeColor
import io.github.arnavdugad.metropulse.ui.common.hoursText
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

private val ROW_HEIGHT = 58.dp

/** A train on the strip: between station [index] and the next, [fraction] of the way. */
private data class StripTrain(val index: Int, val fraction: Float)

/** A line as a vertical strip map, with its stations and the trains on it by the timetable. */
@Composable
fun LineScreen(
    lineId: String,
    network: TransitNetwork?,
    onBack: () -> Unit,
    onStation: (complexId: String) -> Unit,
    liveViewModel: LiveViewModel = hiltViewModel(),
) {
    val colors = MetroPulseTheme.colors
    val line = network?.lineById?.get(lineId)
    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (network == null || line == null) {
            Column {
                DetailTopBar(onBack)
                EmptyState(MpIcons.Lines, stringResource(R.string.line_gone_title), stringResource(R.string.line_gone_body), Modifier.padding(top = 80.dp))
            }
            return@Box
        }
        val patterns = remember(network, lineId) { network.patterns.filter { it.lineId == lineId } }
        // The longest pattern of each direction stands for that direction.
        val directions = remember(patterns) { patterns.groupBy { it.direction }.toSortedMap().values.map { group -> group.maxBy { it.size } } }
        var direction by rememberSaveable { mutableIntStateOf(0) }
        val pattern = directions[direction.coerceIn(0, directions.lastIndex)]
        val lineColour = line.composeColor()
        val today = remember { ZonedDateTime.now(network.timezone).toLocalDate() }
        val timed = network.hasTimetable(lineId, network.dayType(today)) && network.trips.any { t -> patterns.any { it.id == t.patternId } }
        val clock = rememberClockFormatter()

        // Live trains (London) when the feed reports them, otherwise where the timetable puts them.
        val live by remember(network, lineId) { liveViewModel.line(network, lineId) }.collectAsStateWithLifecycle(null)
        val latestLive by rememberUpdatedState(live)
        var trains by remember { mutableStateOf(emptyList<StripTrain>()) }
        var trainsLive by remember { mutableStateOf(false) }
        LaunchedEffect(network, pattern, timed) {
            val scheduled = if (timed) ScheduledTrains(network, today) else null
            while (true) {
                val now = ZonedDateTime.now(network.timezone)
                val livePositions = latestLive?.let { LivePositions.estimate(network, it, now.toEpochSecond(), setOf(lineId)) }.orEmpty()
                trainsLive = livePositions.isNotEmpty()
                trains = when {
                    trainsLive -> stripTrains(network, pattern, livePositions)
                    scheduled != null -> stripTrains(network, pattern, scheduled.positionsAt(now.toLocalTime().toSecondOfDay().toDouble()))
                    else -> emptyList()
                }
                delay(2_000)
            }
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { DetailTopBar(onBack) }
            item {
                Column(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .sharedElement("line-$lineId")
                        .shadow(10.dp, MpShapes.card, ambientColor = colors.shadow, spotColor = colors.shadow)
                        .clip(MpShapes.card)
                        .background(colors.surface),
                ) {
                    Box(Modifier.fillMaxWidth().height(6.dp).background(lineColour))
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LineDot(line, 14.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(line.name, style = MetroPulseTheme.type.headline, color = colors.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                            when {
                                trainsLive -> TimeSourceChip(TimeSource.Live)
                                timed -> TimeSourceChip(if (line.quality == DataQuality.Approximate) TimeSource.Approximate else TimeSource.Scheduled)
                                else -> Tag(stringResource(R.string.lines_times_unavailable), tint = colors.textSecondary)
                            }
                        }
                        val stations = patterns.flatMap { p -> p.stationIds.map { network.stationById.getValue(it).complexId } }.toSet().size
                        val hours = network.operatingHours(lineId, today)
                        Text(
                            listOfNotNull(
                                pluralStringResource(R.plurals.stations_count, stations, stations),
                                hours?.let { hoursText(it.firstSecs, it.lastSecs, clock) },
                            ).joinToString(" · "),
                            style = MetroPulseTheme.type.body,
                            color = colors.textSecondary,
                        )
                        if (line.operator.isNotBlank()) Text(line.operator, style = MetroPulseTheme.type.caption, color = colors.textTertiary)
                    }
                }
            }
            if (directions.size > 1) item {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    directions.forEachIndexed { i, p ->
                        DirectionChip(stringResource(R.string.line_towards, network.stationById[p.stationIds.last()]?.name.orEmpty()), i == direction) { direction = i }
                    }
                }
            }
            item {
                val provider = liveViewModel.provider(network)
                Text(
                    when {
                        trainsLive && provider != null -> stringResource(R.string.line_trains_live) + " · " + provider.credit
                        timed -> stringResource(R.string.home_trains_by_timetable)
                        else -> stringResource(R.string.home_trains_none)
                    },
                    style = MetroPulseTheme.type.caption,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(pattern.stationIds, key = { i, id -> "$direction-$i-$id" }) { i, stationId ->
                val station = network.stationById.getValue(stationId)
                StripRow(
                    name = station.name,
                    colour = lineColour,
                    first = i == 0,
                    last = i == pattern.size - 1,
                    others = network.linesAtComplex[station.complexId].orEmpty().filter { it.id != lineId },
                    trains = trains.filter { it.index == i },
                    onClick = { onStation(station.complexId) },
                )
            }
        }
    }
}

@Composable
private fun DirectionChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    Text(
        text,
        style = MetroPulseTheme.type.label,
        color = if (selected) colors.onAccent else colors.textPrimary,
        maxLines = 1,
        modifier = Modifier
            .clip(MpShapes.pill)
            .background(if (selected) colors.accent else colors.surface)
            .clickable(role = Role.RadioButton) { haptics.tick(); onClick() }
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun StripRow(
    name: String,
    colour: Color,
    first: Boolean,
    last: Boolean,
    others: List<io.github.arnavdugad.metropulse.core.domain.transit.Line>,
    trains: List<StripTrain>,
    onClick: () -> Unit,
) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier.fillMaxWidth().height(ROW_HEIGHT).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp).height(ROW_HEIGHT), contentAlignment = Alignment.Center) {
            Column(Modifier.height(ROW_HEIGHT), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(6.dp).weight(1f).background(if (first) Color.Transparent else colour))
                Box(Modifier.width(6.dp).weight(1f).background(if (last) Color.Transparent else colour))
            }
            val interchange = others.isNotEmpty()
            Box(
                Modifier
                    .size(if (interchange) 20.dp else 16.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(if (interchange) 4.dp else 3.5.dp, if (interchange) colors.textPrimary else colour, CircleShape),
            )
            // Trains between this station and the next, drawn on the rail below the node.
            trains.forEach { t ->
                Box(
                    Modifier
                        .offset(y = ROW_HEIGHT * t.fraction)
                        .size(22.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(colour)
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MpIcons.Train, null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(name, style = if (others.isNotEmpty()) MetroPulseTheme.type.bodyStrong else MetroPulseTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { others.forEach { LineDot(it) } }
    }
}

/** Places trains of this line's direction on the strip, by the stations they are between. */
private fun stripTrains(network: TransitNetwork, strip: Pattern, positions: List<io.github.arnavdugad.metropulse.core.domain.transit.TrainPosition>): List<StripTrain> {
    val indexOf = strip.stationIds.withIndex().associate { (i, id) -> id to i }
    return positions.mapNotNull { pos ->
        if (pos.lineId != strip.lineId) return@mapNotNull null
        val p = network.patternById[pos.patternId] ?: return@mapNotNull null
        if (p.direction != strip.direction) return@mapNotNull null
        val d = p.stopDistM
        val seg = (0 until p.size - 1).firstOrNull { pos.alongM < d[it + 1] } ?: return@mapNotNull null
        val from = indexOf[p.stationIds[seg]] ?: return@mapNotNull null
        val to = indexOf[p.stationIds[seg + 1]] ?: return@mapNotNull null
        if (to != from + 1) return@mapNotNull null
        val f = ((pos.alongM - d[seg]) / (d[seg + 1] - d[seg]).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
        StripTrain(from, f)
    }
}
