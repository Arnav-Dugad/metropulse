package io.github.arnavdugad.metropulse.core.data.city

import androidx.test.core.app.ApplicationProvider
import io.github.arnavdugad.metropulse.core.data.city.db.CityDatabase
import io.github.arnavdugad.metropulse.core.domain.fare.FareEngine
import io.github.arnavdugad.metropulse.core.domain.routing.JourneyPlanner
import io.github.arnavdugad.metropulse.core.domain.routing.Leg
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * Opens every bundled city database through Room (so the pipeline's DDL must match the entities
 * exactly), loads it into the domain model and plans a journey on it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CityDatabaseTest {

    @get:Rule val tmp = TemporaryFolder()

    private val assets = File("../../app/src/main/assets/cities")
    private val monday = LocalDate.of(2026, 11, 2)

    private suspend fun load(cityId: String): TransitNetwork {
        val copy = File(tmp.root, "$cityId.db")
        File(assets, "$cityId.db").copyTo(copy, overwrite = true)
        val db = CityDatabase.open(ApplicationProvider.getApplicationContext(), copy)
        try {
            return CityNetworkLoader.load(db.dao())
        } finally {
            db.close()
        }
    }

    @Test
    fun `every bundled city opens in Room and plans a journey`() = runTest {
        val cities = CityManifest.parse(Json { ignoreUnknownKeys = true }, File(assets, "manifest.json").readText())
        for (city in cities) {
            val network = load(city.id)
            assertEquals(city.id, network.cityId)
            assertEquals(city.version, network.dataVersion)
            assertEquals("${city.id} stations", city.stationCount, network.stations.size)
            assertTrue("${city.id} complexes", network.complexes.size <= network.stations.size)

            // Ends of the longest pattern.
            val p = network.patterns.maxBy { it.size }
            val from = network.stationById.getValue(p.stationIds.first()).complexId
            val to = network.stationById.getValue(p.stationIds.last()).complexId
            val result = JourneyPlanner(network).plan(from, to, monday, 9 * 3600)
            assertFalse("${city.id}: no journey ${network.complexName(from)} -> ${network.complexName(to)}", result.journeys.isEmpty())
            val best = result.journeys.first()
            for (leg in best.legs) assertTrue("${city.id}: negative leg", leg.durationSecs >= 0)
            if (best.timed) {
                assertTrue(best.arriveSecs!! > best.departSecs!!)
                assertTrue("${city.id}: departs before query", best.departSecs!! >= 9 * 3600)
            }
            val fare = FareEngine(network).quote(best, monday, best.departSecs)
            println(
                "${city.id}: ${network.complexName(from)} -> ${network.complexName(to)} " +
                    "${best.rides.joinToString(" + ") { it.lineId }} ${if (best.timed) "timed" else "untimed"} " +
                    "${best.durationSecs / 60} min, fare ${fare?.totalPaise?.let { it / 100.0 }}, " +
                    "media ${fare?.media?.joinToString { "${it.media} ${it.paise / 100.0}" }}",
            )
        }
    }

    @Test
    fun `bengaluru uses the official fare table and timed departures`() = runTest {
        val network = load("bengaluru")
        val majestic = network.complexes.keys.first { network.complexName(it).contains("Majestic", ignoreCase = true) }
        val whitefield = network.complexes.keys.first { network.complexName(it).contains("Whitefield", ignoreCase = true) }
        val journey = JourneyPlanner(network).plan(majestic, whitefield, monday, 9 * 3600).journeys.first()
        assertTrue(journey.timed)
        assertEquals(0, journey.transfers)
        val quote = FareEngine(network).quote(journey, monday, journey.departSecs)!!
        assertTrue("fare ${quote.totalPaise}", quote.totalPaise!! in 3000..10000)
        assertTrue(quote.media.any { it.media == "smart_card" && it.paise < quote.totalPaise!! })
    }

    @Test
    fun `delhi journeys are untimed but routed across interchanges`() = runTest {
        val network = load("delhi")
        fun complex(name: String) = network.complexes.keys.first { network.complexName(it).equals(name, ignoreCase = true) }
        val result = JourneyPlanner(network).plan(complex("Rajiv Chowk"), complex("Hauz Khas"), monday, 9 * 3600)
        val j = result.journeys.first()
        assertFalse(j.timed)
        assertTrue(result.untimedLines.isNotEmpty())
        assertTrue(j.rides.isNotEmpty())
        assertTrue(j.legs.none { it is Leg.Ride && it.departSecs != null })
        // Rajiv Chowk to Hauz Khas is on the Yellow Line, direct.
        assertEquals(listOf("del-yellow"), j.lineSequence)
        val fare = FareEngine(network).quote(j, monday, null)!!
        assertTrue("fare ${fare.totalPaise}", fare.totalPaise!! in 2000..6400)
    }
}
