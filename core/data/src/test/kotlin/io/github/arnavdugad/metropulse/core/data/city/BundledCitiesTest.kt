package io.github.arnavdugad.metropulse.core.data.city

import io.github.arnavdugad.metropulse.core.domain.city.CityDataPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Guards the databases bundled in app/src/main/assets/cities against a stale or partial rebuild. */
class BundledCitiesTest {

    private val dir = File("../../app/src/main/assets/cities")
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `every manifest entry matches its bundled database`() {
        val cities = CityManifest.parse(json, File(dir, "manifest.json").readText())
        assertTrue("no cities in the bundled manifest", cities.isNotEmpty())
        for (city in cities) {
            val db = File(dir, "${city.id}.db")
            assertTrue("${city.id}.db is missing", db.isFile)
            assertEquals("${city.id} size", city.sizeBytes, db.length())
            assertEquals("${city.id} sha256", city.sha256, sha256(db))
            assertEquals("${city.id} schema", CityDataPolicy.SUPPORTED_SCHEMA, city.schemaVersion)
            assertTrue("${city.id} url", city.url.endsWith("/${city.id}.db"))
        }
        val bundled = dir.listFiles { f -> f.extension == "db" }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals("databases without a manifest entry", cities.map { it.id }.toSet(), bundled)
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
