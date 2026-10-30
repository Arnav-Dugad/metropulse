package io.github.arnavdugad.metropulse.core.data.user

import io.github.arnavdugad.metropulse.core.domain.user.SavedJourney
import io.github.arnavdugad.metropulse.core.domain.user.SavedStation
import io.github.arnavdugad.metropulse.core.domain.user.TripRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Favourite stations, favourite journeys and recent journeys, kept on the device only. */
@Singleton
class UserRepository @Inject constructor(private val dao: UserDao) {

    fun favouriteStations(cityId: String): Flow<List<SavedStation>> =
        dao.favouriteStations(cityId).map { list -> list.map { SavedStation(it.cityId, it.complexId, it.name, it.directionKey, it.directionLabel) } }

    suspend fun setFavouriteStation(station: SavedStation, favourite: Boolean) {
        if (favourite) {
            dao.upsert(
                FavouriteStationEntity(station.cityId, station.complexId, station.name, System.currentTimeMillis(), station.directionKey, station.directionLabel),
            )
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

    val trips: Flow<List<TripRecord>> = dao.trips().map { list ->
        list.map {
            TripRecord(
                it.cityId, it.fromComplex, it.fromName, it.toComplex, it.toName, it.startedAt, it.endedAt, it.rideSecs, it.distanceM,
                it.fareMinor, it.currency, it.lines.split(',').filter(String::isNotEmpty), it.arrived,
            )
        }
    }

    suspend fun recordTrip(t: TripRecord) = dao.insert(
        TripEntity(
            cityId = t.cityId, fromComplex = t.fromComplexId, toComplex = t.toComplexId, fromName = t.fromName, toName = t.toName,
            startedAt = t.startedAt, endedAt = t.endedAt, rideSecs = t.rideSecs, distanceM = t.distanceM, fareMinor = t.fareMinor,
            currency = t.currency, lines = t.lineIds.joinToString(","), arrived = t.arrived,
        ),
    )

    suspend fun clearTrips() = dao.clearTrips()

    /**
     * How much the rider uses each station (complex id -> weight), for ranking search results:
     * favourites count most, then favourite journeys, recent plans and finished trips.
     */
    fun stationUsage(cityId: String): Flow<Map<String, Int>> =
        combine(favouriteStations(cityId), favouriteJourneys(cityId), recentJourneys(cityId), trips) { stations, journeys, recents, trips ->
            buildMap<String, Int> {
                fun add(id: String, n: Int) = put(id, (get(id) ?: 0) + n)
                stations.forEach { add(it.complexId, 5) }
                journeys.forEach { add(it.fromComplexId, 3); add(it.toComplexId, 3) }
                recents.forEach { add(it.fromComplexId, it.useCount); add(it.toComplexId, it.useCount) }
                trips.filter { it.cityId == cityId }.forEach { add(it.fromComplexId, 1); add(it.toComplexId, 1) }
            }
        }

    private companion object {
        const val RECENT_LIMIT = 20
    }
}
