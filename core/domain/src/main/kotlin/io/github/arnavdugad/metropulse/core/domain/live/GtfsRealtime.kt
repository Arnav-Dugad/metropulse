package io.github.arnavdugad.metropulse.core.domain.live

/**
 * The parts of a GTFS-realtime FeedMessage MetroPulse uses: per trip, the route and the predicted
 * arrival at each upcoming stop, plus where the vehicle is. Decoded with a minimal protobuf reader
 * (field numbers from gtfs-realtime.proto), so no protobuf runtime is needed.
 */
data class RtFeed(val timestamp: Long, val trips: List<RtTrip>)

data class RtTrip(
    val tripId: String,
    val routeId: String,
    /** Upcoming stops in order, with predicted times (epoch seconds; null when only one of the two is given). */
    val stops: List<RtStop>,
    /** From a VehiclePosition for the same trip, when the feed has one. */
    val vehicle: RtVehicle? = null,
)

data class RtStop(val stopId: String, val arrival: Long?, val departure: Long?) {
    val time: Long? get() = arrival ?: departure
}

enum class RtStatus { IncomingAt, StoppedAt, InTransitTo }

data class RtVehicle(val stopId: String?, val status: RtStatus)

object GtfsRealtime {

    fun parse(bytes: ByteArray): RtFeed {
        var timestamp = 0L
        val updates = LinkedHashMap<String, RtTrip>()
        val vehicles = HashMap<String, RtVehicle>()
        ProtoReader(bytes).forEach { field, r ->
            when (field) {
                1 -> r.message().forEach { f, h -> if (f == 3) timestamp = h.varint() else h.skip() } // FeedHeader.timestamp
                2 -> parseEntity(r.message(), updates, vehicles)
                else -> r.skip()
            }
        }
        val trips = updates.values.map { it.copy(vehicle = vehicles[it.tripId]) }
        return RtFeed(timestamp, trips)
    }

    private fun parseEntity(r: ProtoReader, updates: MutableMap<String, RtTrip>, vehicles: MutableMap<String, RtVehicle>) {
        r.forEach { field, h ->
            when (field) {
                3 -> parseTripUpdate(h.message())?.let { updates[it.tripId] = it } // FeedEntity.trip_update
                4 -> parseVehicle(h.message())?.let { (trip, v) -> vehicles[trip] = v } // FeedEntity.vehicle
                else -> h.skip()
            }
        }
    }

    private class TripDescriptor(var tripId: String = "", var routeId: String = "")

    private fun parseTrip(r: ProtoReader): TripDescriptor {
        val t = TripDescriptor()
        r.forEach { f, h ->
            when (f) {
                1 -> t.tripId = h.string()
                5 -> t.routeId = h.string()
                else -> h.skip()
            }
        }
        return t
    }

    private fun parseTripUpdate(r: ProtoReader): RtTrip? {
        var trip = TripDescriptor()
        val stops = ArrayList<RtStop>()
        r.forEach { f, h ->
            when (f) {
                1 -> trip = parseTrip(h.message())
                2 -> stops += parseStopTimeUpdate(h.message())
                else -> h.skip()
            }
        }
        if (trip.tripId.isEmpty()) return null
        return RtTrip(trip.tripId, trip.routeId, stops)
    }

    private fun parseStopTimeUpdate(r: ProtoReader): RtStop {
        var stopId = ""
        var arrival: Long? = null
        var departure: Long? = null
        r.forEach { f, h ->
            when (f) {
                2 -> arrival = eventTime(h.message())
                3 -> departure = eventTime(h.message())
                4 -> stopId = h.string()
                else -> h.skip()
            }
        }
        return RtStop(stopId, arrival, departure)
    }

    /** StopTimeEvent.time (field 2); 0 or absent means no time. */
    private fun eventTime(r: ProtoReader): Long? {
        var time: Long? = null
        r.forEach { f, h -> if (f == 2) time = h.varint().takeIf { it > 0 } else h.skip() }
        return time
    }

    private fun parseVehicle(r: ProtoReader): Pair<String, RtVehicle>? {
        var trip = TripDescriptor()
        var stopId: String? = null
        var status = RtStatus.InTransitTo // the protobuf default
        r.forEach { f, h ->
            when (f) {
                1 -> trip = parseTrip(h.message())
                4 -> status = when (h.varint().toInt()) {
                    0 -> RtStatus.IncomingAt
                    1 -> RtStatus.StoppedAt
                    else -> RtStatus.InTransitTo
                }
                7 -> stopId = h.string()
                else -> h.skip()
            }
        }
        if (trip.tripId.isEmpty()) return null
        return trip.tripId to RtVehicle(stopId, status)
    }
}

/** Reads protobuf wire format: varints, length-delimited fields and fixed-width fields. */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    private var wireType = 0

    /** Calls [block] for each field; the block must consume the value (read it or [skip] it). */
    fun forEach(block: (field: Int, reader: ProtoReader) -> Unit) {
        while (pos < end) {
            val key = varint()
            wireType = (key and 7).toInt()
            block((key ushr 3).toInt(), this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw IllegalArgumentException("truncated varint")
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IllegalArgumentException("varint too long")
        }
    }

    private fun length(): Int {
        val n = varint().toInt()
        if (n < 0 || pos + n > end) throw IllegalArgumentException("bad length $n")
        return n
    }

    fun message(): ProtoReader {
        val n = length()
        return ProtoReader(buf, pos, pos + n).also { pos += n }
    }

    /** A protobuf `float` (wire type 5, little-endian). */
    fun float(): Float {
        if (pos + 4 > end) throw IllegalArgumentException("truncated float")
        var bits = 0
        for (i in 0 until 4) bits = bits or ((buf[pos + i].toInt() and 0xFF) shl (8 * i))
        pos += 4
        return Float.fromBits(bits)
    }

    fun string(): String {
        val n = length()
        return String(buf, pos, n, Charsets.UTF_8).also { pos += n }
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> {
                val n = length() // read first: `pos += length()` would use pos from before the prefix
                pos += n
            }
            5 -> pos += 4
            else -> throw IllegalArgumentException("unsupported wire type $wireType")
        }
    }
}
