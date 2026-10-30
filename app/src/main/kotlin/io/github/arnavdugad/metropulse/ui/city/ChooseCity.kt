package io.github.arnavdugad.metropulse.ui.city

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.component.PrimaryButton
import io.github.arnavdugad.metropulse.core.designsystem.component.TextAction
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.ui.common.EmptyState

/** Shown wherever city data is needed before the rider has picked a city. */
@Composable
fun ChooseCity(cityViewModel: CityViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(MpIcons.Pin, stringResource(R.string.city_choose_title), stringResource(R.string.city_choose_body))
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.city_choose_action), onClick = cityViewModel::showPicker, icon = MpIcons.Search)
    }
}

/** The city's data could not be read: say so plainly and offer a retry or another city. */
@Composable
fun CityFailed(cityViewModel: CityViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        EmptyState(MpIcons.Info, stringResource(R.string.city_failed_title), stringResource(R.string.city_failed_body))
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.action_retry), onClick = cityViewModel::retry, icon = MpIcons.Refresh)
        Spacer(Modifier.height(4.dp))
        TextAction(stringResource(R.string.city_choose_other), onClick = cityViewModel::showPicker)
    }
}
