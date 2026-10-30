package io.github.arnavdugad.metropulse.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.component.pressScale
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.tabular
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import io.github.arnavdugad.metropulse.core.domain.user.SavedJourney
import io.github.arnavdugad.metropulse.ui.city.ChooseCity
import io.github.arnavdugad.metropulse.ui.city.CityViewModel
import io.github.arnavdugad.metropulse.ui.common.ChoiceChip
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.LineChip
import io.github.arnavdugad.metropulse.ui.common.RoundIconButton
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.ui.common.StationPickerSheet
import io.github.arnavdugad.metropulse.ui.common.StatusBarScrim
import io.github.arnavdugad.metropulse.ui.common.formatDuration
import io.github.arnavdugad.metropulse.ui.common.formatMoney
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import java.time.LocalTime

private enum class Picking { From, To }

@Composable
fun PlanScreen(planViewModel: PlanViewModel, cityViewModel: CityViewModel, onOpenJourney: (index: Int) -> Unit) {
    val state by cityViewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is CityState.Ready -> Planner(planViewModel, s.city, onOpenJourney)
        CityState.NoCity -> ChooseCity(cityViewModel)
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MetroPulseTheme.colors.accent) }
    }
}

@Composable
private fun Planner(vm: PlanViewModel, city: LoadedCity, onOpenJourney: (Int) -> Unit) {
    val colors = MetroPulseTheme.colors
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val favourites by vm.favourites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val stepFree by vm.stepFree.collectAsStateWithLifecycle()
    val suggestion by vm.commuteSuggestion.collectAsStateWithLifecycle()
    var comparing by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val network = city.network

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = SCREEN_GUTTER, end = SCREEN_GUTTER, bottom = 130.dp)) {
            item {
                Text(
                    stringResource(R.string.plan_title),
                    style = MetroPulseTheme.type.display,
                    color = colors.textPrimary,
                    modifier = Modifier.statusBarsPadding().padding(top = 12.dp, bottom = 16.dp).semantics { heading() },
                )
                Endpoints(
                    from = query.from?.let(network::complexName),
                    to = query.to?.let(network::complexName),
                    onFrom = { picking = Picking.From },
                    onTo = { picking = Picking.To },
                    onSwap = vm::swap,
                )
                Spacer(Modifier.height(12.dp))
                TimeChoices(query.departAt, stepFree, onChoose = vm::setDepartAt, onPickTime = { pickingTime = true }, onStepFree = vm::setStepFree)
            }
            when (val r = results) {
                PlanResults.Planning -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                }
                PlanResults.NoRoute -> item {
                    EmptyState(MpIcons.Route, stringResource(R.string.plan_no_route_title), stringResource(R.string.plan_no_route_body), Modifier.padding(top = 40.dp))
                }
                is PlanResults.Ready -> {
                    if (r.notStepFree.isNotEmpty()) item {
                        Notice(MpIcons.Alert, stringResource(R.string.plan_step_free_endpoint, r.notStepFree.joinToString()), colors.approximate)
                    }
                    if (r.droppedForAccess > 0) item {
                        Notice(MpIcons.Accessible, pluralStringResource(R.plurals.plan_step_free_dropped, r.droppedForAccess, r.droppedForAccess), colors.textSecondary)
                    }
                    if (r.options.isEmpty()) item {
                        EmptyState(MpIcons.Accessible, stringResource(R.string.plan_step_free_none_title), stringResource(R.string.plan_step_free_none_body), Modifier.padding(top = 32.dp))
                    }
                    if (r.options.isNotEmpty()) item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionTitle(stringResource(R.string.plan_options), Modifier.weight(1f))
                            if (r.options.size > 1) {
                                ChoiceChip(stringResource(R.string.plan_compare), comparing) { comparing = !comparing }
                            }
                        }
                    }
                    if (comparing && r.options.size > 1) item {
                        Comparison(city, r.options, onOpen = onOpenJourney, modifier = Modifier.padding(bottom = 12.dp).animateItem())
                    }
                    itemsIndexed(r.options, key = { i, _ -> "option-$i" }) { i, option ->
                        JourneyCard(city, option, i, onClick = { onOpenJourney(i) }, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    if (r.options.any { !it.journey.timed }) item {
                        Text(stringResource(R.string.plan_untimed_note), style = MetroPulseTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(4.dp))
                    }
                }
                PlanResults.Idle -> {
                    suggestion?.let { j ->
                        item(key = "commute-suggestion") {
                            CommuteSuggestion(
                                city, j,
                                onAccept = { home -> vm.acceptCommute(j, home) },
                                onDismiss = { vm.dismissCommute(j) },
                                modifier = Modifier.padding(top = 16.dp).animateItem(),
                            )
                        }
                    }
                    if (favourites.isEmpty() && recents.isEmpty()) {
                        item { EmptyState(MpIcons.Route, stringResource(R.string.plan_empty_title), stringResource(R.string.plan_empty_body), Modifier.padding(top = 48.dp)) }
                    }
                    if (favourites.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.plan_favourites)) }
                        itemsIndexed(favourites, key = { _, f -> "fav-${f.fromComplexId}-${f.toComplexId}" }) { _, f -> SavedRow(city, f, MpIcons.StarFilled, onClick = { vm.open(f) }) }
                    }
                    if (recents.isNotEmpty()) {
                        item { SectionTitle(stringResource(R.string.plan_recent)) }
                        itemsIndexed(recents, key = { _, r -> "recent-${r.fromComplexId}-${r.toComplexId}" }) { _, r -> SavedRow(city, r, MpIcons.Clock, onClick = { vm.open(r) }) }
                    }
                }
            }
        }
        StatusBarScrim()
    }

    picking?.let { which ->
        StationPickerSheet(
            city = city,
            title = stringResource(if (which == Picking.From) R.string.plan_pick_from else R.string.plan_pick_to),
            onPick = { id ->
                if (which == Picking.From) vm.setFrom(id) else vm.setTo(id)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
    if (pickingTime) {
        TimeDialog(initial = query.departAt ?: LocalTime.now(network.timezone), onDismiss = { pickingTime = false }) {
            vm.setDepartAt(it)
            pickingTime = false
        }
    }
}

@Composable
private fun Endpoints(from: String?, to: String?, onFrom: () -> Unit, onTo: () -> Unit, onSwap: () -> Unit) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp, MpShapes.card, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(MpShapes.card)
            .background(colors.surface)
            .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            EndpointRow(MpIcons.Locate, stringResource(R.string.onboard_from), from ?: stringResource(R.string.onboard_choose_station), from == null, onFrom)
            HorizontalDivider(Modifier.padding(start = 44.dp), color = colors.hairline)
            EndpointRow(MpIcons.Pin, stringResource(R.string.onboard_to), to ?: stringResource(R.string.onboard_choose_destination), to == null, onTo)
        }
        RoundIconButton(MpIcons.Swap, stringResource(R.string.plan_swap), onSwap, colors.accent)
    }
}

@Composable
private fun EndpointRow(icon: ImageVector, label: String, value: String, placeholder: Boolean, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(MpShapes.control).clickable(role = Role.Button, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, style = MetroPulseTheme.type.caption, color = colors.textSecondary)
            Text(value, style = MetroPulseTheme.type.bodyStrong, color = if (placeholder) colors.textTertiary else colors.textPrimary)
        }
    }
}

@Composable
private fun TimeChoices(departAt: LocalTime?, stepFree: Boolean, onChoose: (LocalTime?) -> Unit, onPickTime: () -> Unit, onStepFree: (Boolean) -> Unit) {
    val clock = rememberClockFormatter()
    Row(
        Modifier.bleed(SCREEN_GUTTER).horizontalScroll(rememberScrollState()).padding(horizontal = SCREEN_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip(stringResource(R.string.plan_now), departAt == null) { onChoose(null) }
        for (minutes in listOf(15L, 30L, 60L)) {
            ChoiceChip(stringResource(R.string.plan_in_minutes, minutes.toInt()), false) { onChoose(LocalTime.now().plusMinutes(minutes).withSecond(0).withNano(0)) }
        }
        ChoiceChip(departAt?.let { stringResource(R.string.plan_leave_at, clock(it.toSecondOfDay())) } ?: stringResource(R.string.plan_pick_time), departAt != null, onClick = onPickTime)
        ChoiceChip(stringResource(R.string.plan_step_free), stepFree) { onStepFree(!stepFree) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val colors = MetroPulseTheme.colors
    val state = rememberTimePickerState(initial.hour, initial.minute, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(stringResource(R.string.plan_leave_at_title), color = colors.textPrimary) },
        text = {
            TimePicker(
                state,
                colors = TimePickerDefaults.colors(
                    clockDialSelectedContentColor = colors.onAccent,
                    selectorColor = colors.accent,
                    timeSelectorSelectedContainerColor = colors.accentSoft,
                    timeSelectorSelectedContentColor = colors.accent,
                ),
            )
        },
        confirmButton = { TextButton({ onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.action_done), color = colors.accent) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.update_cancel), color = colors.textSecondary) } },
    )
}

/** One planned option: lines, times (or an honest "about"), changes, fare. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JourneyCard(city: LoadedCity, option: PlanOption, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val journey = option.journey
    val network = city.network
    val interaction = remember { MutableInteractionSource() }
    val haptics = rememberMpHaptics()
    Column(
        modifier
            .fillMaxWidth()
            .sharedElement("journey-$index")
            .pressScale(interaction, 0.98f)
            .shadow(8.dp, MpShapes.card, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(MpShapes.card)
            .background(colors.surface)
            .clickable(interaction, indication = null, role = Role.Button) { haptics.tick(); onClick() }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (journey.timed) {
                Text("${clock(journey.departSecs!!)} → ${clock(journey.arriveSecs!!)}", style = MetroPulseTheme.type.title.tabular(), color = colors.textPrimary)
            } else {
                Text(stringResource(R.string.plan_about, formatDuration(journey.durationSecs)), style = MetroPulseTheme.type.title, color = colors.textPrimary)
            }
            Spacer(Modifier.weight(1f))
            if (journey.timed) TimeSourceChip(if (journey.quality == DataQuality.Approximate) TimeSource.Approximate else TimeSource.Scheduled)
            else Tag(stringResource(R.string.lines_times_unavailable), tint = colors.textSecondary)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            journey.rides.forEachIndexed { i, ride ->
                val line = network.lineById[ride.lineId] ?: return@forEachIndexed
                if (i > 0) Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                LineChip(line)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val changes = if (journey.transfers == 0) stringResource(R.string.onboard_direct) else pluralStringResource(R.plurals.onboard_changes, journey.transfers, journey.transfers)
            Text(
                if (journey.timed) "${formatDuration(journey.durationSecs)} · $changes" else changes,
                style = MetroPulseTheme.type.body,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            option.fare?.totalPaise?.let { paise ->
                val best = option.fare.media.minByOrNull { it.paise }
                Text(
                    if (best != null) stringResource(R.string.plan_fare_with_card, formatMoney(paise, city.network.currency), formatMoney(best.paise, city.network.currency)) else formatMoney(paise, city.network.currency),
                    style = MetroPulseTheme.type.label,
                    color = colors.textPrimary,
                )
            }
        }
        if (option.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (OptionTag.Fastest in option.tags) Tag(stringResource(R.string.plan_fastest))
                if (OptionTag.FewestChanges in option.tags) Tag(stringResource(R.string.plan_fewest_changes))
            }
        }
        if (option.stepFreeUnknown.isNotEmpty()) {
            Text(
                stringResource(R.string.plan_step_free_unknown, option.stepFreeUnknown.joinToString()),
                style = MetroPulseTheme.type.caption,
                color = colors.textTertiary,
            )
        }
    }
}

/** A one-line note above the options (step-free warnings). */
@Composable
private fun Notice(icon: ImageVector, text: String, tint: Color) {
    val colors = MetroPulseTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp).clip(MpShapes.control).background(tint.copy(alpha = 0.10f)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MetroPulseTheme.type.body, color = colors.textPrimary)
    }
}

/** The options side by side as a table; the best value in each column is highlighted. */
@Composable
private fun Comparison(city: LoadedCity, options: List<PlanOption>, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val clock = rememberClockFormatter()
    val currency = city.network.currency
    // A column's best is highlighted only where the options differ in it.
    fun <T : Comparable<T>> best(values: List<T>): T? = values.minOrNull()?.takeIf { values.distinct().size > 1 }
    val bestTime = best(options.mapNotNull { o -> o.journey.arriveSecs })
    val bestChanges = best(options.map { it.journey.transfers })
    val bestWalk = best(options.map { it.walkSecs })
    val bestFare = best(options.mapNotNull { it.fare?.totalPaise })
    Column(
        modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface).padding(vertical = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            CompareCell(stringResource(R.string.plan_compare_arrive), header = true, weight = 1.3f)
            CompareCell(stringResource(R.string.plan_compare_time), header = true)
            CompareCell(stringResource(R.string.plan_compare_changes), header = true)
            CompareCell(stringResource(R.string.plan_compare_walk), header = true)
            CompareCell(stringResource(R.string.plan_compare_fare), header = true, weight = 1.2f)
        }
        HorizontalDivider(color = colors.hairline)
        options.forEachIndexed { i, o ->
            val j = o.journey
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(i) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompareCell(j.arriveSecs?.let(clock) ?: "—", best = j.arriveSecs != null && j.arriveSecs == bestTime, weight = 1.3f)
                CompareCell(formatDuration(j.durationSecs))
                CompareCell(j.transfers.toString(), best = j.transfers == bestChanges)
                CompareCell(if (o.walkSecs == 0) "—" else formatDuration(o.walkSecs), best = o.walkSecs == bestWalk)
                CompareCell(o.fare?.totalPaise?.let { formatMoney(it, currency) } ?: "—", best = o.fare?.totalPaise != null && o.fare.totalPaise == bestFare, weight = 1.2f)
            }
        }
    }
}

@Composable
private fun RowScope.CompareCell(text: String, header: Boolean = false, best: Boolean = false, weight: Float = 1f) {
    val colors = MetroPulseTheme.colors
    Text(
        text,
        style = if (header) MetroPulseTheme.type.caption else MetroPulseTheme.type.label.tabular(),
        color = when {
            header -> colors.textTertiary
            best -> colors.accent
            else -> colors.textPrimary
        },
        maxLines = 1,
        modifier = Modifier.weight(weight),
    )
}

/** "You often go A ↔ B": one tap makes it the commute (reminders, widget, quick tile). */
@Composable
private fun CommuteSuggestion(city: LoadedCity, journey: SavedJourney, onAccept: (home: String) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MetroPulseTheme.colors
    val network = city.network
    val from = network.complexes[journey.fromComplexId]?.let { network.complexName(journey.fromComplexId) } ?: journey.fromName
    val to = network.complexes[journey.toComplexId]?.let { network.complexName(journey.toComplexId) } ?: journey.toName
    Column(
        modifier.fillMaxWidth().clip(MpShapes.card).background(colors.accentSoft).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(MpIcons.History, null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.plan_commute_suggest_title), style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.plan_commute_suggest_body, from, to), style = MetroPulseTheme.type.body, color = colors.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(R.string.plan_commute_home_is, from), false) { onAccept(journey.fromComplexId) }
            ChoiceChip(stringResource(R.string.plan_commute_home_is, to), false) { onAccept(journey.toComplexId) }
        }
        TextButton(onDismiss, Modifier.align(Alignment.End)) { Text(stringResource(R.string.plan_commute_not_now), color = colors.textSecondary) }
    }
}

@Composable
private fun SavedRow(city: LoadedCity, saved: SavedJourney, icon: ImageVector, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val network = city.network
    val from = network.complexes[saved.fromComplexId]?.let { network.complexName(saved.fromComplexId) } ?: saved.fromName
    val to = network.complexes[saved.toComplexId]?.let { network.complexName(saved.toComplexId) } ?: saved.toName
    Row(
        Modifier.fillMaxWidth().clip(MpShapes.control).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(saved.label.ifBlank { from }, style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary)
            Text(stringResource(R.string.plan_to_station, to), style = MetroPulseTheme.type.caption, color = colors.textSecondary)
        }
        Icon(MpIcons.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }
}

private val SCREEN_GUTTER = 20.dp

/** Widens a row by [gutter] on both sides so it scrolls to the screen edges inside a padded list. */
private fun Modifier.bleed(gutter: Dp) = layout { measurable, constraints ->
    val extra = gutter.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}
