package io.github.arnavdugad.metropulse.core.data.city.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// The city database layout. tools/metropipe writes the files from Room's exported schema
// (schemas/.../CityDatabase/<version>.json), so these entities are the single source of truth.
// Conventions: times are seconds after the service day's midnight (may exceed 86 400); dates are
// yyyymmdd integers; colours are 0xRRGGBB; polylines are Google-encoded with precision 6.

/** Key/value facts about the file: city_id, schema_version, data_version, timezone, ... */
@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "line")
data class LineEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "long_name") val longName: String,
    val color: Int,
    @ColumnInfo(name = "text_color") val textColor: Int,
    /** metro | light_rail | monorail */
    val mode: String,
    val operator: String,
    /** scheduled | estimated | approximate */
    val quality: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
)

/** Published first/last departures, present even where headways are not published. */
@Entity(tableName = "line_hours", primaryKeys = ["line_id", "day_type"])
data class LineHoursEntity(
    @ColumnInfo(name = "line_id") val lineId: String,
    /** weekday | saturday | sunday */
    @ColumnInfo(name = "day_type") val dayType: String,
    @ColumnInfo(name = "first_secs") val firstSecs: Int,
    @ColumnInfo(name = "last_secs") val lastSecs: Int,
)

@Entity(tableName = "station", indices = [Index("complex_id")])
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** JSON object of OSM name:xx tags, kept for future localisation. */
    @ColumnInfo(name = "local_names") val localNames: String,
    @ColumnInfo(name = "osm_id") val osmId: String?,
    /** Platforms of one interchange share this; shown as one station. */
    @ColumnInfo(name = "complex_id") val complexId: String,
)

@Entity(tableName = "pattern", indices = [Index("line_id")])
data class PatternEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "line_id") val lineId: String,
    val direction: Int,
    val headsign: String,
    val shape: String,
    @ColumnInfo(name = "length_m") val lengthM: Double,
)

@Entity(tableName = "pattern_stop", primaryKeys = ["pattern_id", "seq"], indices = [Index("station_id")])
data class PatternStopEntity(
    @ColumnInfo(name = "pattern_id") val patternId: Int,
    val seq: Int,
    @ColumnInfo(name = "station_id") val stationId: String,
    /** Distance along the pattern's shape. */
    @ColumnInfo(name = "dist_m") val distM: Double,
)

/** Run times shared by trips: comma-separated offsets in seconds from the first departure. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "pattern_id") val patternId: Int,
    val arrivals: String,
    val departures: String,
)

@Entity(tableName = "service")
data class ServiceEntity(
    @PrimaryKey val id: String,
    /** Mon = 1, Tue = 2 ... Sun = 64 */
    val days: Int,
    @ColumnInfo(name = "start_date") val startDate: Int,
    @ColumnInfo(name = "end_date") val endDate: Int,
)

@Entity(tableName = "service_exception", primaryKeys = ["service_id", "date"])
data class ServiceExceptionEntity(
    @ColumnInfo(name = "service_id") val serviceId: String,
    val date: Int,
    /** 1 = runs on this date, 0 = does not */
    val added: Int,
)

@Entity(tableName = "trip", indices = [Index("pattern_id")])
data class TripEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "pattern_id") val patternId: Int,
    @ColumnInfo(name = "profile_id") val profileId: Int,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "start_secs") val startSecs: Int,
)

/** Walking links; from = to is the minimum time to change platforms within a station. */
@Entity(tableName = "transfer", primaryKeys = ["from_station", "to_station"])
data class TransferEntity(
    @ColumnInfo(name = "from_station") val fromStation: String,
    @ColumnInfo(name = "to_station") val toStation: String,
    @ColumnInfo(name = "walk_secs") val walkSecs: Int,
)

/** Physical track for the 3D map. */
@Entity(tableName = "track")
data class TrackEntity(
    @PrimaryKey val id: Int,
    /** elevated | underground | at_grade */
    val kind: String,
    /** comma-separated */
    @ColumnInfo(name = "line_ids") val lineIds: String,
    val geometry: String,
)

@Entity(tableName = "station_exit", indices = [Index("station_id")])
data class StationExitEntity(
    @PrimaryKey val id: Int,
    @ColumnInfo(name = "station_id") val stationId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** 1 yes, 0 no, null unknown */
    val wheelchair: Int?,
)

/** Official origin-destination fares (a city uses this or fare_rule). */
@Entity(tableName = "fare_od", primaryKeys = ["from_station", "to_station"])
data class FareOdEntity(
    @ColumnInfo(name = "from_station") val fromStation: String,
    @ColumnInfo(name = "to_station") val toStation: String,
    @ColumnInfo(name = "fare_paise") val farePaise: Int,
)

@Entity(tableName = "fare_rule")
data class FareRuleEntity(
    @PrimaryKey val id: Int,
    /** km | stations */
    val kind: String,
    /** Inclusive upper bound of the slab. */
    val upper: Double,
    @ColumnInfo(name = "fare_paise") val farePaise: Int,
    /** all | weekday | holiday */
    @ColumnInfo(name = "day_type") val dayType: String,
    /** "" = whole network; otherwise journeys within this line's fare system. */
    @ColumnInfo(name = "line_id") val lineId: String,
)

@Entity(tableName = "fare_discount")
data class FareDiscountEntity(
    @PrimaryKey val id: Int,
    /** smart_card | qr_ticket | ncmc */
    val media: String,
    /** all | peak | offpeak */
    val timeframe: String,
    /** all | weekday | holiday */
    @ColumnInfo(name = "day_type") val dayType: String,
    @ColumnInfo(name = "line_id") val lineId: String,
    val percent: Double,
    val note: String,
)

@Entity(tableName = "fare_peak_window")
data class FarePeakWindowEntity(
    @PrimaryKey val id: Int,
    /** bitmask as in service.days */
    val days: Int,
    @ColumnInfo(name = "start_secs") val startSecs: Int,
    @ColumnInfo(name = "end_secs") val endSecs: Int,
)

@Entity(tableName = "source")
data class SourceEntity(
    @PrimaryKey val id: Int,
    val what: String,
    val url: String,
    val licence: String,
    val attribution: String,
)
