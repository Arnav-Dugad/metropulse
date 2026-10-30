package io.github.arnavdugad.metropulse.core.domain.live

import io.github.arnavdugad.metropulse.core.domain.geo.LatLon
import io.github.arnavdugad.metropulse.core.domain.geo.haversineM

/** One vehicle from a GTFS-realtime VehiclePositions feed. */
data class RtPosition(
    val vehicleId: String,
    val routeId: String,
    val tripId: String,
    val location: LatLon,
    /** When the vehicle reported this position (epoch seconds; 0 if the feed gives none). */
    val timestamp: Long,
)

/**
 * Decodes a VehiclePositions feed (Delhi's buses publish nothing else): the vehicles that report a
 * position, with their route. Field numbers from gtfs-realtime.proto.
 */
object VehiclePositions {

    fun parse(bytes: ByteArray): List<RtPosition> {
        val out = ArrayList<RtPosition>()
        ProtoReader(bytes).forEach { field, r ->
            if (field == 2) {
                r.message().forEach { f, h -> if (f == 4) parseVehicle(h.message(), out) else h.skip() } // FeedEntity.vehicle
            } else {
                r.skip()
            }
        }
        return out
    }

    private fun parseVehicle(r: ProtoReader, out: MutableList<RtPosition>) {
        var tripId = ""
        var routeId = ""
        var lat = Float.NaN
        var lon = Float.NaN
        var timestamp = 0L
        var vehicleId = ""
        r.forEach { f, h ->
            when (f) {
                1 -> h.message().forEach { tf, th ->
                    when (tf) {
                        1 -> tripId = th.string()
                        5 -> routeId = th.string()
                        else -> th.skip()
                    }
                }
                2 -> h.message().forEach { pf, ph ->
                    when (pf) {
                        1 -> lat = ph.float()
                        2 -> lon = ph.float()
                        else -> ph.skip()
                    }
                }
                5 -> timestamp = h.varint()
                8 -> h.message().forEach { vf, vh -> if (vf == 1) vehicleId = vh.string() else vh.skip() }
                else -> h.skip()
            }
        }
        if (lat.isNaN() || lon.isNaN() || (lat == 0f && lon == 0f)) return
        out += RtPosition(vehicleId.ifEmpty { tripId }, routeId, tripId, LatLon(lat.toDouble(), lon.toDouble()), timestamp)
    }
}

/** A bus route's public name ("534") and where it runs ("Anand Vihar ISBT - Mehrauli"). */
data class BusRoute(val name: String, val description: String?)

enum class BusMotion { Approaching, Leaving }

data class NearbyBus(
    val vehicleId: String,
    val routeId: String,
    val route: BusRoute?,
    val distanceM: Int,
    /** Seconds since the bus reported its position. */
    val ageSecs: Int,
    /** From its previous reported position; null when it has not moved or was not seen before. */
    val motion: BusMotion?,
)

/** The buses near a station, nearest route first, each route's buses nearest first. */
data class BusSnapshot(val buses: List<NearbyBus>, val fetchedAtEpochSec: Long, val radiusM: Int) {
    val byRoute: List<List<NearbyBus>> get() = buses.groupBy { it.route?.name ?: it.routeId }.values.sortedBy { it.first().distanceM }
}

object NearbyBuses {

    /**
     * Buses within [radiusM] of any of [points] (a station's entrances or platforms) that reported in
     * the last [maxAgeSecs]. [previous] holds where each vehicle was in the feed before, to tell
     * whether it is coming or going; a move under [MIN_MOVE_M] counts as neither.
     */
    fun near(
        positions: List<RtPosition>,
        points: List<LatLon>,
        routes: Map<String, BusRoute>,
        nowEpochSec: Long,
        radiusM: Int = 1_000,
        maxAgeSecs: Int = 300,
        previous: Map<String, LatLon> = emptyMap(),
    ): List<NearbyBus> {
        if (points.isEmpty()) return emptyList()
        fun distance(at: LatLon) = points.minOf { haversineM(it, at) }
        return positions.mapNotNull { p ->
            val age = if (p.timestamp > 0) (nowEpochSec - p.timestamp).coerceAtLeast(0) else 0
            if (age > maxAgeSecs) return@mapNotNull null
            val d = distance(p.location)
            if (d > radiusM) return@mapNotNull null
            val motion = previous[p.vehicleId]?.let { before ->
                val delta = d - distance(before)
                when {
                    delta <= -MIN_MOVE_M -> BusMotion.Approaching
                    delta >= MIN_MOVE_M -> BusMotion.Leaving
                    else -> null
                }
            }
            NearbyBus(p.vehicleId, p.routeId, routes[p.routeId], d.toInt(), age.toInt(), motion)
        }.sortedBy { it.distanceM }
    }

    private const val MIN_MOVE_M = 25.0
}
