package io.github.arnavdugad.metropulse.core.domain.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationSearchTest {

    private val search = StationSearch(
        listOf(
            "Rajiv Chowk", "Kashmere Gate", "Rajouri Garden", "Mahatma Gandhi Road", "M.G. Road", "Majestic",
            "Sector 18", "Sector 52", "Hauz Khas", "Dilli Haat - INA", "Netaji Subhash Place", "Chandni Chowk",
        ).map { SearchEntry(it, it) },
    )

    private fun top(q: String) = search.search(q, limit = 3).map { it.name }

    @Test
    fun `exact and prefix matches come first`() {
        assertEquals("Rajiv Chowk", top("rajiv chowk").first())
        assertEquals(listOf("Rajiv Chowk", "Rajouri Garden"), top("raj").take(2).sorted())
        assertEquals("Hauz Khas", top("hauz").first())
    }

    @Test
    fun `word prefixes and punctuation`() {
        assertEquals("Dilli Haat - INA", top("ina").first())
        assertEquals("M.G. Road", top("mg road").first())
        assertEquals("M.G. Road", top("MG Rd").first())
        assertEquals("Netaji Subhash Place", top("subhash").first())
    }

    @Test
    fun `typos are tolerated`() {
        assertEquals("Rajiv Chowk", top("rajiv chok").first())
        assertEquals("Kashmere Gate", top("kashmiri gate").first())
        assertEquals("Majestic", top("majestc").first())
        assertEquals("Chandni Chowk", top("chandi chowk").first())
    }

    @Test
    fun `numbers must match`() {
        assertEquals("Sector 18", top("sector 18").first())
        assertEquals("Sector 52", top("sec 52").first())
    }

    @Test
    fun `a whole word beats a near miss`() {
        val bengaluru = StationSearch(
            listOf("Dr. B. R. Ambedkar Station, Vidhana Soudha", "Rashtreeya Vidyalaya Road", "Vijayanagar").map { SearchEntry(it, it) },
        )
        assertEquals("Dr. B. R. Ambedkar Station, Vidhana Soudha", bengaluru.search("vidhana").first().name)
        assertEquals("Dr. B. R. Ambedkar Station, Vidhana Soudha", bengaluru.search("vidhan soudha").first().name)
        assertEquals("Rashtreeya Vidyalaya Road", bengaluru.search("vidyalaya").first().name)
    }

    @Test
    fun `nonsense finds nothing`() {
        assertTrue(search.search("qqqzzz").isEmpty())
        assertTrue(search.search("  ").isEmpty())
    }

    @Test
    fun `damerau levenshtein counts adjacent swaps once`() {
        assertEquals(1, StationSearch.damerauLevenshtein("chwok", "chowk"))
        assertEquals(3, StationSearch.damerauLevenshtein("kitten", "sitting"))
    }
}
