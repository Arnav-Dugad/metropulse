package io.github.arnavdugad.metropulse.core.data.user

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UserDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UserDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()

    @After fun close() = db.close()

    @Test
    fun `recent journeys move to the top, count uses and are trimmed`() = runTest {
        dao.recordJourney("blr", "a", "A", "b", "B", now = 1, keep = 2)
        dao.recordJourney("blr", "c", "C", "d", "D", now = 2, keep = 2)
        dao.recordJourney("blr", "a", "A", "b", "B", now = 3, keep = 2)
        var recent = dao.recentJourneys("blr", 10).first()
        assertEquals(listOf("a", "c"), recent.map { it.fromComplex })
        assertEquals(2, recent.first().useCount)

        dao.recordJourney("blr", "e", "E", "f", "F", now = 4, keep = 2)
        recent = dao.recentJourneys("blr", 10).first()
        assertEquals(listOf("e", "a"), recent.map { it.fromComplex })
        assertTrue("other cities untouched", dao.recentJourneys("del", 10).first().isEmpty())
    }

    @Test
    fun `favourite stations per city`() = runTest {
        dao.upsert(FavouriteStationEntity("blr", "majestic", "Majestic", 1))
        dao.upsert(FavouriteStationEntity("del", "rajiv", "Rajiv Chowk", 2))
        assertEquals(listOf("majestic"), dao.favouriteStations("blr").first().map { it.complexId })
        dao.removeFavouriteStation("blr", "majestic")
        assertTrue(dao.favouriteStations("blr").first().isEmpty())
    }
}
