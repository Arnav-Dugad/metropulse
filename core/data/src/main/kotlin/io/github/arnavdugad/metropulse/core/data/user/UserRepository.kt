package io.github.arnavdugad.metropulse.core.data.user

import io.github.arnavdugad.metropulse.core.domain.user.SavedJourney
import io.github.arnavdugad.metropulse.core.domain.user.SavedStation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Favourite stations, favourite journeys and recent journeys, kept on the device only. */
@Singleton
class UserRepository @Inject constructor(private val dao: UserDao) {

    fun favouriteStations(cityId: String): Flow<List<SavedStation>> =
        dao.favouriteStations(cityId).map { list -> list.map { SavedStation(it.cityId, it.complexId, it.name) } }

    suspend fun setFavouriteStation(station: SavedStation, favourite: Boolean) {
        if (favourite) {
            dao.upsert(FavouriteStationEntity(station.cityId, station.complexId, station.name, System.currentTimeMillis()))
        } else {
            dao.removeFavouriteStation(station.cityId, station.complexId)
        }
    }

    fun favouriteJourneys(cityId: String): Flow<List<SavedJourney>> =
        dao.favouriteJourneys(cityId).map { list ->
            list.map { SavedJourney(it.cityId, it.fromComplex, it.fromName, it.toComplex, it.toName, it.label, it.addedAt) }
        }

    suspend fun setFavouriteJourney(journey: SavedJourney, favourite: Boolean) {
        if (favourite) {
            dao.upsert(
                FavouriteJourneyEntity(
                    journey.cityId, journey.fromComplexId, journey.toComplexId, journey.fromName, journey.toName,
                    journey.label, System.currentTimeMillis(),
                ),
            )
        } else {
            dao.removeFavouriteJourney(journey.cityId, journey.fromComplexId, journey.toComplexId)
        }
    }

    fun recentJourneys(cityId: String, limit: Int = RECENT_LIMIT): Flow<List<SavedJourney>> =
        dao.recentJourneys(cityId, limit).map { list ->
            list.map { SavedJourney(it.cityId, it.fromComplex, it.fromName, it.toComplex, it.toName, "", it.usedAt, it.useCount) }
        }

    suspend fun recordJourney(cityId: String, fromComplexId: String, fromName: String, toComplexId: String, toName: String) {
        dao.recordJourney(cityId, fromComplexId, fromName, toComplexId, toName, System.currentTimeMillis(), RECENT_LIMIT)
    }

    suspend fun clearRecent(cityId: String) = dao.clearRecent(cityId)

    private companion object {
        const val RECENT_LIMIT = 20
    }
}
