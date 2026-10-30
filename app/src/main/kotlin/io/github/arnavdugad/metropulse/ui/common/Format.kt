package io.github.arnavdugad.metropulse.ui.common

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.arnavdugad.metropulse.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats seconds after a service day's midnight as a clock time ("05:30" or "5:30 am"). */
@Composable
fun rememberClockFormatter(): (Int) -> String {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    return remember(is24) { clockFormatter(is24) }
}

/** The same outside Compose (widgets, notifications). */
fun clockFormatter(context: Context): (Int) -> String = clockFormatter(DateFormat.is24HourFormat(context))

private fun clockFormatter(is24: Boolean): (Int) -> String {
    val formatter = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.ENGLISH)
    return { secs -> LocalTime.ofSecondOfDay(((secs % 86_400) + 86_400L) % 86_400).format(formatter).lowercase() }
}

/** "₹30", "£2.80" or "AED 7.50": fares are kept in minor units (paise, pence, cents, fils). */
fun formatMoney(minor: Int, currency: String): String {
    val prefix = CURRENCY_PREFIX[currency] ?: "$currency "
    val amount = if (minor % 100 == 0) "${minor / 100}" else "%d.%02d".format(Locale.ENGLISH, minor / 100, minor % 100)
    return prefix + amount
}

/** Symbols where riders know them; Gulf currencies read best as their codes. */
private val CURRENCY_PREFIX = mapOf("INR" to "₹", "GBP" to "£", "USD" to "$", "EUR" to "€")

/** "7 min" or "1 h 5 min". */
fun formatDuration(secs: Int): String {
    val minutes = ((secs + 30) / 60).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

/** "First 5:35 am · Last 12:30 am", or "From 5:50 am, through the night" for night services (Night Tube). */
@Composable
fun hoursText(firstSecs: Int, lastSecs: Int, clock: (Int) -> String): String =
    if (lastSecs - firstSecs >= ALL_NIGHT_SECS) stringResource(R.string.station_all_night, clock(firstSecs))
    else stringResource(R.string.lines_hours, clock(firstSecs), clock(lastSecs))

private const val ALL_NIGHT_SECS = 20 * 3600
