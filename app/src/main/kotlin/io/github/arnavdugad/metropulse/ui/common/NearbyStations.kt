package io.github.arnavdugad.metropulse.ui.common

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.geo.haversineM
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.onboard.OnboardService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A station near the rider, found from the phone's location. */
data class NearbyStation(val complexId: String, val distanceM: Double)

/**
 * Finds the stations within walking distance of the phone, from one short high-accuracy request
 * (a balanced one may never switch GPS on and leave only a stale fix). Does nothing without the
 * location permission. The location is used on the phone only.
 */
@Singleton
class NearbyStations @Inject constructor(@ApplicationContext private val context: Context) {

    private val _nearby = MutableStateFlow<List<NearbyStation>>(emptyList())

    /** Closest first, within [LIMIT_M]. */
    val nearby: StateFlow<List<NearbyStation>> = _nearby.asStateFlow()

    private var locating: Pair<LocationManager, LocationListenerCompat>? = null

    @SuppressLint("MissingPermission") // checked first
    fun locate(network: TransitNetwork) {
        if (!OnboardService.hasLocationPermission(context)) return
        val manager = context.getSystemService<LocationManager>() ?: return
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.allProviders.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        manager.getLastKnownLocation(provider)?.let { _nearby.value = around(network, it) }
        stop()
        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                _nearby.value = around(network, location)
                if (location.accuracy <= GOOD_ACCURACY_M) stop()
            }
        }
        locating = manager to listener
        LocationManagerCompat.requestLocationUpdates(
            manager, provider,
            LocationRequestCompat.Builder(1_000).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).setDurationMillis(LOCATE_FOR_MS).build(),
            ContextCompat.getMainExecutor(context), listener,
        )
    }

    fun stop() {
        locating?.let { (manager, listener) -> LocationManagerCompat.removeUpdates(manager, listener) }
        locating = null
    }

    private fun around(network: TransitNetwork, location: Location): List<NearbyStation> {
        val here = LatLon(location.latitude, location.longitude)
        return network.complexes.map { (id, members) -> NearbyStation(id, members.minOf { haversineM(here, it.location) }) }
            .filter { it.distanceM <= LIMIT_M }
            .sortedBy { it.distanceM }
            .take(MAX)
    }

    private companion object {
        const val LIMIT_M = 1_500.0
        const val MAX = 5
        const val GOOD_ACCURACY_M = 50f
        const val LOCATE_FOR_MS = 20_000L
    }
}
