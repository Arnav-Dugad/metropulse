package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity

/** A bottom sheet to pick a station: search, or one of the [suggestions] (nearby stations, say). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPickerSheet(
    city: LoadedCity,
    title: String,
    onPick: (complexId: String) -> Unit,
    onDismiss: () -> Unit,
    suggestions: List<NearbyStation> = emptyList(),
) {
    val colors = MetroPulseTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
        var query by rememberSaveable { mutableStateOf("") }
        val usage = LocalStationUsage.current
        val hits = remember(query, city, usage) { city.search.search(query, 30, usage) }
        val network = city.network
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            Text(
                title,
                style = MetroPulseTheme.type.headline,
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 12.dp).semantics { heading() },
            )
            SearchField(query, { query = it }, stringResource(R.string.search_hint), autoFocus = true, container = colors.background)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 6.dp, bottom = 16.dp)) {
                if (query.isBlank() && suggestions.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_nearby)) }
                    items(suggestions, key = { "near-" + it.complexId }) { near ->
                        StationRow(
                            name = network.complexName(near.complexId),
                            lines = network.linesAtComplex[near.complexId].orEmpty(),
                            detail = stringResource(R.string.search_distance, near.distanceM.toInt()),
                            leading = MpIcons.Locate,
                            onClick = { onPick(near.complexId) },
                        )
                    }
                }
                items(hits, key = { it.complexId }) { hit ->
                    StationRow(hit.name, network.linesAtComplex[hit.complexId].orEmpty(), onClick = { onPick(hit.complexId) })
                }
            }
        }
    }
}
