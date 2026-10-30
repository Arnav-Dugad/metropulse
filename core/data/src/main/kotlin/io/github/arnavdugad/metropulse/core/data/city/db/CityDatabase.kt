package io.github.arnavdugad.metropulse.core.data.city.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.arnavdugad.metropulse.core.domain.city.CityDataPolicy
import java.io.File

/**
 * One city's network, as a read-only file produced by tools/build_data.py. The version is the
 * data schema version ([CityDataPolicy.SUPPORTED_SCHEMA]); a file with another version is refused
 * rather than migrated.
 */
@Database(
    entities = [
        MetaEntity::class, LineEntity::class, LineHoursEntity::class, StationEntity::class, PatternEntity::class,
        PatternStopEntity::class, ProfileEntity::class, ServiceEntity::class, ServiceExceptionEntity::class,
        TripEntity::class, TransferEntity::class, TrackEntity::class, StationExitEntity::class, FareOdEntity::class,
        FareRuleEntity::class, FareDiscountEntity::class, FarePeakWindowEntity::class, SourceEntity::class,
    ],
    version = CityDataPolicy.SUPPORTED_SCHEMA,
    exportSchema = true,
)
abstract class CityDatabase : RoomDatabase() {
    abstract fun dao(): CityDao

    companion object {
        /**
         * Opens an installed city file. TRUNCATE journaling keeps the file byte-identical (WAL
         * would rewrite its header), and no migration is configured, so an unexpected schema fails
         * instead of being wiped.
         */
        fun open(context: Context, file: File): CityDatabase =
            Room.databaseBuilder(context, CityDatabase::class.java, file.absolutePath)
                .setJournalMode(JournalMode.TRUNCATE)
                .build()
    }
}

@Dao
interface CityDao {
    @Query("SELECT * FROM meta") suspend fun meta(): List<MetaEntity>
    @Query("SELECT * FROM line ORDER BY sort_order") suspend fun lines(): List<LineEntity>
    @Query("SELECT * FROM line_hours") suspend fun lineHours(): List<LineHoursEntity>
    @Query("SELECT * FROM station") suspend fun stations(): List<StationEntity>
    @Query("SELECT * FROM pattern ORDER BY id") suspend fun patterns(): List<PatternEntity>
    @Query("SELECT * FROM pattern_stop ORDER BY pattern_id, seq") suspend fun patternStops(): List<PatternStopEntity>
    @Query("SELECT * FROM profile") suspend fun profiles(): List<ProfileEntity>
    @Query("SELECT * FROM service") suspend fun services(): List<ServiceEntity>
    @Query("SELECT * FROM service_exception") suspend fun serviceExceptions(): List<ServiceExceptionEntity>
    @Query("SELECT * FROM trip") suspend fun trips(): List<TripEntity>
    @Query("SELECT * FROM transfer") suspend fun transfers(): List<TransferEntity>
    @Query("SELECT * FROM track") suspend fun tracks(): List<TrackEntity>
    @Query("SELECT * FROM station_exit") suspend fun exits(): List<StationExitEntity>
    @Query("SELECT * FROM fare_od") suspend fun fareOd(): List<FareOdEntity>
    @Query("SELECT * FROM fare_rule") suspend fun fareRules(): List<FareRuleEntity>
    @Query("SELECT * FROM fare_discount") suspend fun fareDiscounts(): List<FareDiscountEntity>
    @Query("SELECT * FROM fare_peak_window") suspend fun farePeakWindows(): List<FarePeakWindowEntity>
    @Query("SELECT * FROM source ORDER BY id") suspend fun sources(): List<SourceEntity>
}
