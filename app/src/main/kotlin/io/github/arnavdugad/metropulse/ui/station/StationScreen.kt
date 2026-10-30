package io.github.arnavdugad.metropulse.ui.station

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.data.city.CityRepository
import io.github.arnavdugad.metropulse.core.data.user.UserRepository
import io.github.arnavdugad.metropulse.core.designsystem.component.SecondaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.Tag
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSource
import io.github.arnavdugad.metropulse.core.designsystem.component.TimeSourceChip
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.city.CityState
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.transit.Departure
import io.github.arnavdugad.metropulse.core.domain.transit.DepartureGroup
import io.github.arnavdugad.metropulse.core.domain.live.LiveDepartures
import io.github.arnavdugad.metropulse.core.domain.live.LiveGroup
import io.github.arnavdugad.metropulse.core.domain.transit.Departures
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.user.SavedStation
import io.github.arnavdugad.metropulse.ui.common.DetailTopBar
import io.github.arnavdugad.metropulse.ui.common.LiveCredit
import io.github.arnavdugad.metropulse.ui.common.LiveViewModel
import io.github.arnavdugad.metropulse.ui.common.EmptyState
import io.github.arnavdugad.metropulse.ui.common.LineChip
import io.github.arnavdugad.metropulse.ui.common.LineDot
import io.github.arnavdugad.metropulse.ui.common.RoundIconButton
import io.github.arnavdugad.metropulse.ui.common.SectionTitle
import io.github.arnavdugad.metropulse.ui.common.hoursText
import io.github.arnavdugad.metropulse.ui.common.rememberClockFormatter
import io.github.arnavdugad.metropulse.ui.common.sharedElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject

/** The rider's favourite stations in the current city. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class FavouriteStationsViewModel @Inject constructor(cities: CityRepository, private val user: UserRepository) : ViewModel() {
    val favourites: StateFlow<List<SavedStation>> = cities.state
        .map { (it as? CityState.Ready)?.city?.network?.cityId }
        .flatMapLatest { id -> id?.let(user::favouriteStations) ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setFavourite(station: SavedStation, favourite: Boolean) {
        viewModelScope.launch { user.setFavouriteStation(station, favourite) }
    }
}

@Composable
fun StationScreen(
    complexId: String,
    network: TransitNetwork?,
    favouritesViewModel: FavouriteStationsViewModel,
    onBack: () -> Unit,
    onPlanFrom: () -> Unit,
    onPlanTo: () -> Unit,
    onShowOnMap: () -> Unit,
    onLine: (lineId: String) -> Unit,
    liveViewModel: LiveViewModel = hiltViewModel(),
) {
    val colors = MetroPulseTheme.colors
    val favourites by favouritesViewModel.favourites.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(colors.background)) {
        if (network == null || network.complexes[complexId] == null) {
            DetailTopBar(onBack)
            EmptyState(MpIcons.Pin, stringResource(R.string.station_gone_title), stringResource(R.string.station_gone_body), Modifier.padding(top = 80.dp))
            return@Column
        }
        val name = network.complexName(complexId)
        val favourite = favourites.any { it.complexId == complexId }
        DetailTopBar(onBack) {
            RoundIconButton(
                if (favourite) MpIcons.StarFilled else MpIcons.Star,
                stringResource(if (favourite) R.string.action_unsave else R.string.action_save),
                { favouritesViewModel.setFavourite(SavedStation(network.cityId, complexId, name), !favourite) },
                if (favourite) colors.accent else colors.textPrimary,
            )
        }
        StationDetails(
            complexId, network, favouritesViewModel, liveViewModel, onPlanFrom, onPlanTo, onShowOnMap, onLine,
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        )
    }
}
