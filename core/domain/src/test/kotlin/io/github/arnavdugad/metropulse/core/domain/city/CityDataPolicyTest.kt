package io.github.arnavdugad.metropulse.core.domain.city

import org.junit.Assert.assertEquals
import org.junit.Test

class CityDataPolicyTest {

    private fun city(id: String, version: Long, schema: Int = CityDataPolicy.SUPPORTED_SCHEMA) = CityInfo(
        id, id, "", version, schema, DataQuality.Approximate, TimetableCoverage.Partial, 1, 2, 0.0, 0.0, listOf(0.0, 0.0, 1.0, 1.0), 10, "x", "u",
    )

    @Test
    fun `only newer data for installed cities with a supported schema is offered`() {
        val remote = listOf(city("kochi", 202611010000), city("pune", 202611010000), city("delhi", 202611010000, schema = 2))
        val installed = mapOf("kochi" to 202610290000L, "delhi" to 202610290000L, "chennai" to 1L)
        val updates = CityDataPolicy.updatesFor(remote, installed)
        assertEquals(listOf("kochi"), updates.map { it.city.id })
        assertEquals(202610290000L, updates.single().installedVersion)
    }

    @Test
    fun `same or older version is not an update`() {
        assertEquals(emptyList<CityDataUpdate>(), CityDataPolicy.updatesFor(listOf(city("kochi", 5)), mapOf("kochi" to 5L)))
        assertEquals(emptyList<CityDataUpdate>(), CityDataPolicy.updatesFor(listOf(city("kochi", 4)), mapOf("kochi" to 5L)))
    }

    @Test
    fun `quality parses leniently`() {
        assertEquals(DataQuality.Estimated, DataQuality.parse("ESTIMATED"))
        assertEquals(DataQuality.Approximate, DataQuality.parse("anything"))
    }
}
