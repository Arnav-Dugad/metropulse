package io.github.arnavdugad.metropulse.core.domain.user

/** A station the rider starred (a station complex in one city). */
data class SavedStation(
    val cityId: String,
    val complexId: String,
    val name: String,
    /** Only this way out of the station in widgets ("line|direction|next station"); empty for every way. */
    val directionKey: String = "",
    val directionLabel: String = "",
)

/** A trip taken in on-board mode. */
data class TripRecord(
    val cityId: String,
    val fromComplexId: String,
    val fromName: String,
    val toComplexId: String,
    val toName: String,
    val startedAt: Long,
    val endedAt: Long,
    /** Time on trains, seconds. */
    val rideSecs: Int,
    val distanceM: Int,
    /** Token fare in minor units of [currency], when known. */
    val fareMinor: Int?,
    val currency: String,
    val lineIds: List<String>,
    val arrived: Boolean,
)

/** A journey the rider saved or recently planned. */
data class SavedJourney(
    val cityId: String,
    val fromComplexId: String,
    val fromName: String,
    val toComplexId: String,
    val toName: String,
    /** The rider's label ("Work") for a favourite; empty otherwise. */
    val label: String = "",
    val lastUsedAt: Long = 0,
    val useCount: Int = 0,
)
