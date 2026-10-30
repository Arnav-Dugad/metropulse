package io.github.arnavdugad.metropulse.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

/** Complex id -> how often the rider uses that station (favourites, plans, trips); ranks search results. */
val LocalStationUsage = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }
