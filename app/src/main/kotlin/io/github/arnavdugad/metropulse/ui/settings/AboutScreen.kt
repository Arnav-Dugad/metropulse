package io.github.arnavdugad.metropulse.ui.settings

import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.metropulse.R
import io.github.arnavdugad.metropulse.core.designsystem.icon.MpIcons
import io.github.arnavdugad.metropulse.core.designsystem.theme.MetroPulseTheme
import io.github.arnavdugad.metropulse.core.designsystem.theme.MpShapes
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.ui.common.DetailTopBar
import io.github.arnavdugad.metropulse.ui.common.SectionTitle

/** One credit: who made it, under which licence, and where to read more. */
private data class Credit(val title: String, val licence: String, val links: List<String>)

private val MAP_CREDITS = listOf(
    Credit("© OpenStreetMap contributors", "Open Database License (ODbL) 1.0", listOf("https://www.openstreetmap.org/copyright")),
    Credit("OpenFreeMap", "Map tiles and styles · MIT", listOf("https://openfreemap.org")),
    Credit("OpenMapTiles", "Vector tile schema · BSD 3-Clause / CC BY 4.0", listOf("https://openmaptiles.org")),
    Credit(
        "Positron GL Style",
        "© MapTiler.com & OpenMapTiles contributors, © CartoDB Inc. · BSD 3-Clause; design CC BY 4.0, from CartoDB Basemaps by Stamen and Paul Norman",
        listOf("https://github.com/openmaptiles/positron-gl-style"),
    ),
    Credit("MapLibre Native", "BSD 2-Clause", listOf("https://github.com/maplibre/maplibre-native")),
    Credit("Noto Sans (map labels)", "SIL Open Font License 1.1", listOf("https://fonts.google.com/noto")),
)

/** Live arrivals in London (TfL's transport data terms ask for these notices). */
private val MTA_LIVE_CREDITS = listOf(
    Credit(
        "Metropolitan Transportation Authority (MTA)",
        "Live arrivals in New York · MetroPulse is not licensed or endorsed by the MTA, and the MTA does not warrant that the data is accurate, complete or timely",
        listOf("https://www.mta.info/developers/terms-and-conditions"),
    ),
)

private val LIVE_CREDITS = listOf(
    Credit(
        "Powered by TfL Open Data",
        "Live arrivals in London · Contains OS data © Crown copyright and database rights 2016 and Geomni UK Map data © and database rights 2019",
        listOf("https://tfl.gov.uk/corporate/terms-and-conditions/transport-data-service"),
    ),
)

private val APP_CREDITS = listOf(
    Credit("Inter", "© The Inter Project Authors · SIL Open Font License 1.1", listOf("https://rsms.me/inter/")),
    Credit("Android Jetpack (AndroidX, Compose, Room, WorkManager, Glance)", "Apache License 2.0", listOf("https://developer.android.com/jetpack")),
    Credit("Kotlin and kotlinx", "Apache License 2.0", listOf("https://kotlinlang.org")),
    Credit("Dagger Hilt", "Apache License 2.0", listOf("https://dagger.dev/hilt/")),
    Credit("OkHttp", "Apache License 2.0", listOf("https://square.github.io/okhttp/")),
    Credit("Haze", "Apache License 2.0", listOf("https://github.com/chrisbanes/haze")),
)

private const val LICENCE_URL = "https://github.com/Arnav-Dugad/metropulse/blob/main/LICENSE"

/** Every source and licence the app relies on, including the current city's data sources. */
@Composable
fun AboutScreen(network: TransitNetwork?, version: String, onBack: () -> Unit) {
    val colors = MetroPulseTheme.colors
    // A city's sources often list several pages of one publisher; group them under it.
    val cityCredits = network?.sources.orEmpty()
        .groupBy { it.attribution to it.licence }
        .map { (key, sources) -> Credit(key.first, key.second, sources.map { it.url }.distinct()) }
    Column(Modifier.fillMaxSize().background(colors.background)) {
        DetailTopBar(onBack)
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp)) {
            item {
                Text(
                    stringResource(R.string.about_title),
                    style = MetroPulseTheme.type.display,
                    color = colors.textPrimary,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.about_body, version), style = MetroPulseTheme.type.body, color = colors.textSecondary)
            }
            item { CreditCard(stringResource(R.string.about_app), listOf(Credit("MetroPulse", stringResource(R.string.about_app_licence), listOf(LICENCE_URL)))) }
            if (network != null && cityCredits.isNotEmpty()) {
                item { CreditCard(stringResource(R.string.about_city_data, network.cityName), cityCredits) }
            }
            if (network?.cityId == "london") item { CreditCard(stringResource(R.string.about_live), LIVE_CREDITS) }
            if (network?.cityId == "new-york") item { CreditCard(stringResource(R.string.about_live), MTA_LIVE_CREDITS) }
            item { CreditCard(stringResource(R.string.about_map), MAP_CREDITS) }
            item { CreditCard(stringResource(R.string.about_libraries), APP_CREDITS) }
        }
    }
}

@Composable
private fun CreditCard(title: String, credits: List<Credit>) {
    val colors = MetroPulseTheme.colors
    SectionTitle(title)
    Column(Modifier.fillMaxWidth().clip(MpShapes.card).background(colors.surface)) {
        credits.forEachIndexed { i, credit ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.hairline)
            CreditRow(credit)
        }
    }
}

@Composable
private fun CreditRow(credit: Credit) {
    val colors = MetroPulseTheme.colors
    val uriHandler = LocalUriHandler.current
    var expanded by rememberSaveable(credit.title) { mutableStateOf(false) }
    val shown = if (expanded) credit.links else credit.links.take(COLLAPSED_LINKS)
    Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(credit.title, style = MetroPulseTheme.type.bodyStrong, color = colors.textPrimary)
        Text(credit.licence, style = MetroPulseTheme.type.caption, color = colors.textSecondary)
        for (url in shown) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MpShapes.control)
                    .clickable(role = Role.Button) { uriHandler.openUri(url) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MpIcons.ArrowRight, null, tint = colors.accent, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(shortUrl(url), style = MetroPulseTheme.type.caption, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (credit.links.size > COLLAPSED_LINKS) {
            Text(
                if (expanded) stringResource(R.string.about_fewer) else stringResource(R.string.about_more, credit.links.size - COLLAPSED_LINKS),
                style = MetroPulseTheme.type.label,
                color = colors.textSecondary,
                modifier = Modifier
                    .clip(MpShapes.control)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** "github.com/Vonter/bmrcl-gtfs" rather than the full URL. */
private fun shortUrl(url: String): String {
    val uri = Uri.parse(url)
    val host = uri.host?.removePrefix("www.") ?: return url
    val path = uri.path.orEmpty().trimEnd('/')
    return host + path
}

private const val COLLAPSED_LINKS = 2
