package io.github.arnavdugad.metropulse.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.domain.city.LoadedCity
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.NearbyStations
import io.github.arnavdugad.metropulse.ui.common.RoundIconButton
import io.github.arnavdugad.metropulse.ui.common.SearchField
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.ui.common.StationRow
import io.github.arnavdugad.metropulse.ui.station.FavouriteStationsViewModel
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(val nearbyStations: NearbyStations) : ViewModel() {
    override fun onCleared() = nearbyStations.stop()
}

/** Station search, with nearby and favourite stations before anything is typed. */
@Composable
fun SearchScreen(
    city: LoadedCity?,
    searchViewModel: SearchViewModel,
    favouritesViewModel: FavouriteStationsViewModel,
    onBack: () -> Unit,
    onStation: (complexId: String) -> Unit,
) {
    val colors = MetroPulseTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    val nearby by searchViewModel.nearbyStations.nearby.collectAsStateWithLifecycle()
    val favourites by favouritesViewModel.favourites.collectAsStateWithLifecycle()
    DisposableEffect(city) {
        city?.network?.let(searchViewModel.nearbyStations::locate)
        onDispose { searchViewModel.nearbyStations.stop() }
    }
    Box(Modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp)) {
            item {
                Row(Modifier.statusBarsPadding().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(MpIcons.Back, stringResource(R.string.action_back), onBack)
                    Spacer(Modifier.width(4.dp))
                    SearchField(query, { query = it }, stringResource(R.string.search_hint), Modifier.weight(1f), autoFocus = true)
                }
            }
            if (city == null) return@LazyColumn
            val network = city.network
            if (query.isBlank()) {
                if (nearby.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_nearby)) }
                    items(nearby, key = { "near-" + it.complexId }) { near ->
                        StationRow(
                            name = network.complexName(near.complexId),
                            lines = network.linesAtComplex[near.complexId].orEmpty(),
                            detail = stringResource(R.string.search_distance, near.distanceM.toInt()),
                            leading = MpIcons.Locate,
                            onClick = { onStation(near.complexId) },
                        )
                    }
                }
                val saved = favourites.filter { network.complexes.containsKey(it.complexId) }
                if (saved.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.search_favourites)) }
                    items(saved, key = { "fav-" + it.complexId }) { fav ->
                        StationRow(network.complexName(fav.complexId), network.linesAtComplex[fav.complexId].orEmpty(), { onStation(fav.complexId) }, leading = MpIcons.StarFilled)
                    }
                }
                if (nearby.isEmpty() && saved.isEmpty()) {
                    item { EmptyState(MpIcons.Search, stringResource(R.string.search_empty_title), stringResource(R.string.search_empty_body), Modifier.padding(top = 60.dp)) }
                }
            } else {
                val hits = city.search.search(query, 40)
                if (hits.isEmpty()) {
                    item { EmptyState(MpIcons.Search, stringResource(R.string.search_none_title), stringResource(R.string.search_none_body, query), Modifier.padding(top = 60.dp)) }
                }
                items(hits, key = { it.complexId }) { hit ->
                    StationRow(hit.name, network.linesAtComplex[hit.complexId].orEmpty(), { onStation(hit.complexId) }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
