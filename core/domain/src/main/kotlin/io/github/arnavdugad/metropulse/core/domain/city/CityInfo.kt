package io.github.arnavdugad.metropulse.core.domain.city

/** How a city's (or line's) times were produced. Drives the Scheduled / Approx. labels. */
enum class DataQuality {
    /** The operator's timetable with per-station times. */
    Scheduled,

    /** The operator's departure times, with per-station times modelled from them. */
    Estimated,

    /** Modelled from published first/last trains and headways. */
    Approximate;

    companion object {
        fun parse(value: String): DataQuality = when (value.lowercase()) {
            "scheduled" -> Scheduled
            "estimated" -> Estimated
            else -> Approximate
        }
    }
}

/** How much of a city's network has departure times. */
enum class TimetableCoverage {
    /** Every line has departures on every day type. */
    Full,

    /** Some lines or day types have no published headways and show "times unavailable". */
    Partial,

    /** Only first and last trains are published; no departures are shown. */
    None,

    /** The operator publishes neither departures nor first and last trains. */
    Unknown;

    companion object {
        fun parse(value: String?): TimetableCoverage = when (value?.lowercase()) {
            "full" -> Full
            "none" -> None
            "unknown" -> Unknown
            else -> Partial
        }
    }
}

/** A city whose network data the app can use, as listed in the data manifest. */
data class CityInfo(
    val id: String,
    val name: String,
    val state: String,
    /** Monotonic data version (yyyyMMddHHmm of the build that changed it). */
    val version: Long,
    val schemaVersion: Int,
    val quality: DataQuality,
    val timetable: TimetableCoverage,
    val lineCount: Int,
    val stationCount: Int,
    val centerLat: Double,
    val centerLon: Double,
    /** south, west, north, east */
    val bbox: List<Double>,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
    /** "India" for the Indian systems, which the picker lists first. */
    val country: String = "India",
)

data class CityDataUpdate(val city: CityInfo, val installedVersion: Long)

object CityDataPolicy {
    /** The database layout this app build understands. Newer layouts need an app update first. */
    const val SUPPORTED_SCHEMA = 1

    fun updatesFor(remote: List<CityInfo>, installed: Map<String, Long>): List<CityDataUpdate> =
        remote.mapNotNull { city ->
            val local = installed[city.id] ?: return@mapNotNull null
            if (city.schemaVersion == SUPPORTED_SCHEMA && city.version > local) CityDataUpdate(city, local) else null
        }
}
