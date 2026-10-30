package io.github.arnavdugad.metropulse.ui.city

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.pressScale
import io.github.arnavdugad.metropulse.core.designsystem.component.rememberMpHaptics
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityInfo
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.city.TimetableCoverage
import io.github.arnavdugad.metropulse.core.domain.search.SearchEntry
import io.github.arnavdugad.metropulse.core.domain.search.StationSearch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CityPickerHost(viewModel: CityViewModel) {
    val visible by viewModel.pickerVisible.collectAsStateWithLifecycle()
    if (!visible) return
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = MetroPulseTheme.colors
    val current = when (val s = state) {
        is CityState.Ready -> s.city.network.cityId
        is CityState.Loading -> s.cityId
        is CityState.Failed -> s.cityId
        CityState.NoCity -> null
    }
    ModalBottomSheet(
        onDismissRequest = viewModel::dismissPicker,
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
        CityList(viewModel.cities, current, viewModel::select)
    }
}

/** Searchable list of cities with how their times are made; also the onboarding city page. */
@Composable
fun CityList(
    cities: List<CityInfo>,
    currentId: String?,
    onSelect: (CityInfo) -> Unit,
    modifier: Modifier = Modifier.navigationBarsPadding(),
    showTitle: Boolean = true,
    listModifier: Modifier = Modifier.heightIn(max = 560.dp),
) {
    val colors = MetroPulseTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val search = remember(cities) { StationSearch(cities.map { SearchEntry(it.id, it.name) }) }
    val shown = remember(query, cities) {
        if (query.isBlank()) cities else search.search(query, cities.size).mapNotNull { hit -> cities.firstOrNull { it.id == hit.complexId } }
    }
    Column(modifier) {
        if (showTitle) {
            Text(
                stringResource(R.string.city_picker_title),
                style = MetroPulseTheme.type.headline,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            )
        }
        Row(
            Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(MpShapes.pill)
                .background(colors.background)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MpIcons.Search, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(stringResource(R.string.city_search_hint), style = MetroPulseTheme.type.body, color = colors.textTertiary)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MetroPulseTheme.type.body.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        LazyColumn(
            listModifier,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 20.dp),
        ) {
            // India first, then other countries by name; headers only when more than one country is listed.
            val byCountry = shown.groupBy { it.country }.toSortedMap(compareBy<String>({ it != "India" }, { it }))
            byCountry.forEach { (country, inCountry) ->
                if (byCountry.size > 1) item(key = "country-$country") { SectionTitle(country, Modifier.padding(horizontal = 12.dp)) }
                items(inCountry, key = { it.id }) { city -> CityRow(city, city.id == currentId) { onSelect(city) } }
            }
        }
    }
}

@Composable
private fun CityRow(city: CityInfo, selected: Boolean, onClick: () -> Unit) {
    val colors = MetroPulseTheme.colors
    val haptics = rememberMpHaptics()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.98f)
            .clip(MpShapes.card)
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(interaction, indication = null, role = Role.RadioButton) { haptics.tick(); onClick() }
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(city.name, style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary)
            Text(
                listOfNotNull(
                    city.state.takeIf { it.isNotBlank() },
                    pluralStringResource(R.plurals.lines_count, city.lineCount, city.lineCount),
                    pluralStringResource(R.plurals.stations_count, city.stationCount, city.stationCount),
                ).joinToString(" · "),
                style = MetroPulseTheme.type.caption,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val (label, tint) = timetableLabel(city)
            Tag(label, tint = tint)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(MpIcons.Check, null, tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

/** What the rider gets for times in this city, honestly. */
@Composable
private fun timetableLabel(city: CityInfo): Pair<String, Color> {
    val colors = MetroPulseTheme.colors
    return when {
        city.timetable == TimetableCoverage.Unknown -> stringResource(R.string.lines_times_unavailable) to colors.textSecondary
        city.timetable == TimetableCoverage.None -> stringResource(R.string.city_first_last_only) to colors.textSecondary
        city.quality == DataQuality.Approximate -> stringResource(R.string.city_times_approximate) to colors.approximate
        else -> stringResource(R.string.city_times_scheduled) to colors.scheduled
    }
}
