package io.github.arnavdugad.metropulse.ui.plan

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.PrimaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import io.github.arnavdugad.metropulse.core.domain.fare.FareBasis
import io.github.arnavdugad.metropulse.core.domain.fare.FareQuote
import io.github.arnavdugad.metropulse.core.domain.routing.Leg
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import io.github.arnavdugad.metropulse.ui.common.DetailTopBar
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.RoundIconButton
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.ui.common.composeColor
import io.github.arnavdugad.metropulse.ui.common.formatDuration
import io.github.arnavdugad.metropulse.ui.common.formatMoney
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.sharedElement

/** One planned journey in full: a timeline of rides and changes, the fare, and "Start on board". */
@Composable
fun JourneyScreen(
    index: Int,
    planViewModel: PlanViewModel,
    cityViewModel: CityViewModel,
    onBack: () -> Unit,
    onStation: (complexId: String) -> Unit,
    onStarted: () -> Unit,
) {
    val colors = MetroPulseTheme.colors
    val results by planViewModel.results.collectAsStateWithLifecycle()
    val query by planViewModel.query.collectAsStateWithLifecycle()
    val favourites by planViewModel.favourites.collectAsStateWithLifecycle()
    val city = (cityViewModel.state.collectAsStateWithLifecycle().value as? CityState.Ready)?.city
    val option = (results as? PlanResults.Ready)?.options?.getOrNull(index)
    val from = query.from
    val to = query.to

    Box(Modifier.fillMaxSize().background(colors.background)) {
        if (city == null || option == null || from == null || to == null) {
            Column {
                DetailTopBar(onBack)
                EmptyState(MpIcons.Route, stringResource(R.string.plan_gone_title), stringResource(R.string.plan_gone_body), Modifier.padding(top = 80.dp))
            }
            return@Box
        }
        val favourite = favourites.any { it.fromComplexId == from && it.toComplexId == to }
        val journey = option.journey
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(onBack) {
                RoundIconButton(
                    if (favourite) MpIcons.StarFilled else MpIcons.Star,
                    stringResource(if (favourite) R.string.action_unsave else R.string.action_save),
                    { planViewModel.setFavourite(from, to, !favourite) },
                    if (favourite) colors.accent else colors.textPrimary,
                )
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            ) {
                Summary(city, option, index, from, to)
                SectionTitle(stringResource(R.string.journey_route))
                Timeline(city, journey.legs, onStation)
                option.fare?.let { fare ->
                    SectionTitle(stringResource(R.string.journey_fare))
                    FareCard(fare, city.network.currency)
                }
            }
            Box(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                PrimaryButton(
                    stringResource(R.string.onboard_start),
                    onClick = { if (planViewModel.startOnBoard(from, to)) onStarted() },
                    icon = MpIcons.Train,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Summary(city: LoadedCity, option: PlanOption, index: Int, from: String, to: String) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val journey = option.journey
    val network = city.network
    Column(
        Modifier
            .fillMaxWidth()
            .sharedElement("journey-$index")
            .shadow(10.dp, MpShapes.card, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(MpShapes.card)
            .background(colors.tripBrush)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.plan_to_station, network.complexName(to)).uppercase(), style = MetroPulseTheme.type.overline, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1f))
            TimeSourceChip(
                if (!journey.timed || journey.quality == DataQuality.Approximate) TimeSource.Approximate else TimeSource.Scheduled,
                onGradient = true,
            )
        }
        Text(network.complexName(from), style = MetroPulseTheme.type.headline, color = Color.White)
        Text(
            if (journey.timed) "${clock(journey.departSecs!!)} → ${clock(journey.arriveSecs!!)} · ${formatDuration(journey.durationSecs)}"
            else stringResource(R.string.plan_about, formatDuration(journey.durationSecs)) + " · " + stringResource(R.string.lines_times_unavailable),
            style = MetroPulseTheme.type.bodyStrong,
            color = Color.White.copy(alpha = 0.9f),
        )
        val changes = if (journey.transfers == 0) stringResource(R.string.onboard_direct) else pluralStringResource(R.plurals.onboard_changes, journey.transfers, journey.transfers)
        val stops = journey.rides.sumOf { it.stopCount }
        Text(
            "$changes · " + pluralStringResource(R.plurals.onboard_stops, stops, stops),
            style = MetroPulseTheme.type.body,
            color = Color.White.copy(alpha = 0.8f),
        )
    }
}

/** Rides as coloured rails with their stations; changes and walks as dotted links between them. */
@Composable
private fun Timeline(city: LoadedCity, legs: List<Leg>, onStation: (String) -> Unit) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val network = city.network
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MpShapes.card)
            .background(colors.surface)
            .padding(vertical = 8.dp),
    ) {
        legs.forEachIndexed { i, leg ->
            when (leg) {
                is Leg.Ride -> {
                    // A change at a stop both lines share has no walk leg; still show it.
                    val previous = legs.getOrNull(i - 1)
                    if (previous is Leg.Ride) {
                        val arrived = previous.arriveSecs
                        val departs = leg.departSecs
                        // In whole clock minutes, so it matches the times printed either side.
                        val wait = if (arrived != null && departs != null) (departs / 60 - arrived / 60) * 60 else null
                        WalkLink(wait?.let { stringResource(R.string.journey_change, formatDuration(it)) } ?: stringResource(R.string.journey_change_here))
                    }
                    val line = network.lineById[leg.lineId]
                    val colour = line?.composeColor() ?: colors.accent
                    val pattern = network.patternById[leg.patternId]
                    val from = network.stationById[leg.fromStationId]
                    val to = network.stationById[leg.toStationId]
                    StopNode(colour, from?.name.orEmpty(), leg.departSecs?.let(clock), top = false, bottom = true) { from?.let { onStation(it.complexId) } }
                    var expanded by rememberSaveable(leg.patternId, leg.boardIndex) { mutableStateOf(false) }
                    Rail(colour) {
                        Column(Modifier.animateContentSize().padding(vertical = 6.dp)) {
                            Text(
                                stringResource(R.string.journey_towards, line?.name.orEmpty(), leg.headsign),
                                style = MetroPulseTheme.type.bodyStrong,
                                color = colors.textPrimary,
                            )
                            Text(
                                pluralStringResource(R.plurals.onboard_stops, leg.stopCount, leg.stopCount) + " · " +
                                    (if (leg.departSecs != null) "" else "~") + formatDuration(leg.durationSecs),
                                style = MetroPulseTheme.type.caption,
                                color = colors.textSecondary,
                                modifier = Modifier
                                    .clip(MpShapes.control)
                                    .clickable(role = Role.Button) { expanded = !expanded }
                                    .padding(vertical = 4.dp),
                            )
                            if (expanded && pattern != null) {
                                for (i in leg.boardIndex + 1 until leg.alightIndex) {
                                    val s = network.stationById[pattern.stationIds[i]] ?: continue
                                    Text(
                                        s.name,
                                        style = MetroPulseTheme.type.body,
                                        color = colors.textSecondary,
                                        modifier = Modifier.clickable { onStation(s.complexId) }.padding(vertical = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                    StopNode(colour, to?.name.orEmpty(), leg.arriveSecs?.let(clock), top = true, bottom = false) { to?.let { onStation(it.complexId) } }
                }
                is Leg.Walk -> {
                    val sameComplex = network.stationById[leg.fromStationId]?.complexId == network.stationById[leg.toStationId]?.complexId
                    WalkLink(
                        if (sameComplex) stringResource(R.string.journey_change, formatDuration(leg.durationSecs))
                        else stringResource(R.string.journey_walk, network.stationById[leg.toStationId]?.name.orEmpty(), formatDuration(leg.durationSecs)),
                    )
                }
            }
        }
    }
}

@Composable
private fun StopNode(colour: Color, name: String, time: String?, top: Boolean, bottom: Boolean, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(5.dp).weight(1f).background(if (top) colour else Color.Transparent))
                Box(Modifier.width(5.dp).weight(1f).background(if (bottom) colour else Color.Transparent))
            }
            Box(Modifier.size(16.dp).clip(CircleShape).background(colour))
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.surface))
        }
        Spacer(Modifier.width(14.dp))
        Text(name, style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
        if (time != null) Text(time, style = MetroPulseTheme.type.label, color = colors.textPrimary)
    }
}

@Composable
private fun Rail(colour: Color, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp)) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(Brush.verticalGradient(listOf(colour, colour.copy(alpha = 0.85f)))))
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun WalkLink(text: String) {
    val colors = MetroPulseTheme.colors
    val dash = colors.textTertiary
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.width(4.dp).fillMaxHeight()) {
                drawLine(dash, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }
        }
        Spacer(Modifier.width(14.dp))
        Icon(MpIcons.Walk, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MetroPulseTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(vertical = 14.dp))
    }
}

@Composable
private fun FareCard(fare: FareQuote, currency: String) {
    val colors = MetroPulseTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val total = fare.totalPaise
        if (total == null) {
            Text(stringResource(R.string.journey_fare_unknown), style = MetroPulseTheme.type.body, color = colors.textSecondary)
            return@Column
        }
        FareRow(stringResource(R.string.journey_fare_token), formatMoney(total, currency), bold = true)
        for (m in fare.media) {
            FareRow(stringResource(R.string.journey_fare_media, mediaName(m.media), m.percent.toInt()), formatMoney(m.paise, currency), bold = false)
        }
        val basis = fare.components.mapNotNull { c ->
            when (c.basis) {
                FareBasis.Table -> stringResource(R.string.journey_basis_table)
                FareBasis.Distance -> stringResource(R.string.journey_basis_distance, "%.1f".format(c.measure ?: 0.0))
                FareBasis.Stations -> pluralStringResource(R.plurals.journey_basis_stations, (c.measure ?: 0.0).toInt(), (c.measure ?: 0.0).toInt())
                null -> null
            }
        }.distinct()
        if (fare.components.size > 1) Tag(stringResource(R.string.journey_fare_tickets, fare.components.size), tint = colors.textSecondary)
        if (basis.isNotEmpty()) Text(basis.joinToString(" · "), style = MetroPulseTheme.type.caption, color = colors.textTertiary)
    }
}

@Composable
private fun FareRow(label: String, value: String, bold: Boolean) {
    val colors = MetroPulseTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = if (bold) MetroPulseTheme.type.bodyStrong else MetroPulseTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = if (bold) MetroPulseTheme.type.title else MetroPulseTheme.type.bodyStrong, color = colors.textPrimary)
    }
}

@Composable
private fun mediaName(media: String): String = when (media) {
    "smart_card" -> stringResource(R.string.media_smart_card)
    "qr_ticket" -> stringResource(R.string.media_qr_ticket)
    "ncmc" -> stringResource(R.string.media_ncmc)
    else -> media
}
