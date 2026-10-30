package io.github.arnavdugad.metropulse.core.data.city

import io.github.arnavdugad.metropulse.core.domain.city.CityInfo
import io.github.arnavdugad.metropulse.core.domain.city.DataQuality
import io.github.arnavdugad.metropulse.core.domain.city.TimetableCoverage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `data/manifest.json`, written by tools/build_data.py and bundled as assets/cities/manifest.json. */
@Serializable
internal data class CityManifestDto(
    @SerialName("schema_version") val schemaVersion: Int,
    val cities: List<CityEntryDto> = emptyList(),
)

@Serializable
internal data class CityEntryDto(
    val id: String,
    val name: String,
    val state: String = "",
    val country: String = "India",
    val version: Long,
    @SerialName("schema_version") val schemaVersion: Int,
    val quality: String,
    val timetable: String? = null,
    val lines: Int,
    val stations: Int,
    val center: List<Double>,
    val bbox: List<Double>,
    val size: Long,
    val sha256: String,
    val url: String,
    @SerialName("valid_until") val validUntil: Int? = null,
)

internal object CityManifest {
    fun parse(json: Json, text: String): List<CityInfo> =
        json.decodeFromString(CityManifestDto.serializer(), text).cities.mapNotNull { it.toDomain() }

    private fun CityEntryDto.toDomain(): CityInfo? {
        if (center.size != 2 || bbox.size != 4 || !sha256.matches(Regex("[0-9a-f]{64}"))) return null
        return CityInfo(
            id = id, name = name, state = state, country = country, version = version, schemaVersion = schemaVersion,
            quality = DataQuality.parse(quality), timetable = TimetableCoverage.parse(timetable), lineCount = lines, stationCount = stations,
            centerLat = center[0], centerLon = center[1], bbox = bbox, sizeBytes = size, sha256 = sha256, url = url, validUntil = validUntil,
        )
    }
}
