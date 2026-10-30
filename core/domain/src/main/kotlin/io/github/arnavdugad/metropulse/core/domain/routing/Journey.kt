package io.github.arnavdugad.metropulse.core.domain.routing

import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import java.time.LocalDate

sealed interface Leg {
    val durationSecs: Int

    /** A ride on one train. Times are null when the operator publishes no timetable for it. */
    data class Ride(
        val lineId: String,
        val patternId: Int,
        val headsign: String,
        val boardIndex: Int,
        val alightIndex: Int,
        val fromStationId: String,
        val toStationId: String,
        val departSecs: Int?,
        val arriveSecs: Int?,
        /** Scheduled when timed; otherwise estimated from track length. */
        override val durationSecs: Int,
        val distanceM: Double,
        val tripId: Int?,
    ) : Leg {
        val stopCount: Int get() = alightIndex - boardIndex
    }

    /** A change of platform or a short walk between stations. */
    data class Walk(
        val fromStationId: String,
        val toStationId: String,
        override val durationSecs: Int,
    ) : Leg
}

/**
 * A way from A to B. Clock times are seconds after [serviceDate]'s midnight in the city's time
 * zone, and are null for an untimed journey (a line without a published timetable).
 */
data class Journey(
    val serviceDate: LocalDate,
    val legs: List<Leg>,
    val departSecs: Int?,
    val arriveSecs: Int?,
    val durationSecs: Int,
    /** Worst quality of the times used; meaningless when [timed] is false. */
    val quality: DataQuality,
    val timed: Boolean,
) {
    val rides: List<Leg.Ride> get() = legs.filterIsInstance<Leg.Ride>()
    val transfers: Int get() = (rides.size - 1).coerceAtLeast(0)
    val lineSequence: List<String> get() = rides.map { it.lineId }
}
