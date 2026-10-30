package io.github.arnavdugad.metropulse.core.data.user

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// The rider's own data. Stations are stored by complex id with a copy of the name, so an entry
// still reads sensibly if a data update renames or drops the station.

@Entity(tableName = "favourite_station", primaryKeys = ["city_id", "complex_id"])
data class FavouriteStationEntity(
    @ColumnInfo(name = "city_id") val cityId: String,
    @ColumnInfo(name = "complex_id") val complexId: String,
    val name: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "favourite_journey", primaryKeys = ["city_id", "from_complex", "to_complex"])
data class FavouriteJourneyEntity(
    @ColumnInfo(name = "city_id") val cityId: String,
    @ColumnInfo(name = "from_complex") val fromComplex: String,
    @ColumnInfo(name = "to_complex") val toComplex: String,
    @ColumnInfo(name = "from_name") val fromName: String,
    @ColumnInfo(name = "to_name") val toName: String,
    /** The rider's own label, e.g. "Work". Empty for none. */
    val label: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Entity(tableName = "recent_journey", primaryKeys = ["city_id", "from_complex", "to_complex"])
data class RecentJourneyEntity(
    @ColumnInfo(name = "city_id") val cityId: String,
    @ColumnInfo(name = "from_complex") val fromComplex: String,
    @ColumnInfo(name = "to_complex") val toComplex: String,
    @ColumnInfo(name = "from_name") val fromName: String,
    @ColumnInfo(name = "to_name") val toName: String,
    @ColumnInfo(name = "used_at") val usedAt: Long,
    @ColumnInfo(name = "use_count") val useCount: Int,
)

@Dao
interface UserDao {
    @Query("SELECT * FROM favourite_station WHERE city_id = :cityId ORDER BY added_at")
    fun favouriteStations(cityId: String): Flow<List<FavouriteStationEntity>>

    @Upsert suspend fun upsert(station: FavouriteStationEntity)

    @Query("DELETE FROM favourite_station WHERE city_id = :cityId AND complex_id = :complexId")
    suspend fun removeFavouriteStation(cityId: String, complexId: String)

    @Query("SELECT * FROM favourite_journey WHERE city_id = :cityId ORDER BY added_at")
    fun favouriteJourneys(cityId: String): Flow<List<FavouriteJourneyEntity>>

    @Upsert suspend fun upsert(journey: FavouriteJourneyEntity)

    @Query("DELETE FROM favourite_journey WHERE city_id = :cityId AND from_complex = :from AND to_complex = :to")
    suspend fun removeFavouriteJourney(cityId: String, from: String, to: String)

    @Query("SELECT * FROM recent_journey WHERE city_id = :cityId ORDER BY used_at DESC LIMIT :limit")
    fun recentJourneys(cityId: String, limit: Int): Flow<List<RecentJourneyEntity>>

    @Query("SELECT * FROM recent_journey WHERE city_id = :cityId AND from_complex = :from AND to_complex = :to")
    suspend fun recentJourney(cityId: String, from: String, to: String): RecentJourneyEntity?

    @Upsert suspend fun upsert(recent: RecentJourneyEntity)

    @Query(
        "DELETE FROM recent_journey WHERE city_id = :cityId AND rowid NOT IN " +
            "(SELECT rowid FROM recent_journey WHERE city_id = :cityId ORDER BY used_at DESC LIMIT :keep)",
    )
    suspend fun trimRecent(cityId: String, keep: Int)

    @Query("DELETE FROM recent_journey WHERE city_id = :cityId")
    suspend fun clearRecent(cityId: String)

    /** Records a planned journey, bumping it to the top of the recents. */
    @Transaction
    suspend fun recordJourney(cityId: String, from: String, fromName: String, to: String, toName: String, now: Long, keep: Int) {
        val count = recentJourney(cityId, from, to)?.useCount ?: 0
        upsert(RecentJourneyEntity(cityId, from, to, fromName, toName, now, count + 1))
        trimRecent(cityId, keep)
    }
}

@Database(
    entities = [FavouriteStationEntity::class, FavouriteJourneyEntity::class, RecentJourneyEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun dao(): UserDao
}
