package io.github.arnavdugad.metropulse.core.domain.user

/** A station the rider starred (a station complex in one city). */
data class SavedStation(val cityId: String, val complexId: String, val name: String)

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
