package io.github.arnavdugad.metropulse.core.domain.fare

import io.github.arnavdugad.metropulse.core.domain.routing.JourneyPlanner
import io.github.arnavdugad.metropulse.core.domain.transit.MONDAY
import io.github.arnavdugad.metropulse.core.domain.transit.SUNDAY
import io.github.arnavdugad.metropulse.core.domain.transit.TestNetworkBuilder
import io.github.arnavdugad.metropulse.core.domain.transit.TransitNetwork
import io.github.arnavdugad.metropulse.core.domain.transit.hm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FareEngineTest {

    // A: A1 - A2 - A3 - X (1 km apart).  B: Xb - B1 - B2.
    private fun network(fares: FareData): TransitNetwork = TestNetworkBuilder()
        .station("A1", 0.0, 0.0).station("A2", 1.0, 0.0).station("A3", 2.0, 0.0).station("X", 3.0, 0.0, complex = "X")
        .station("Xb", 3.0, 0.0, complex = "X").station("B1", 4.0, 0.0).station("B2", 5.0, 0.0)
        .route("A", listOf("A1", "A2", "A3", "X"), hm(6, 0), hm(22, 0), 300)
        .route("B", listOf("Xb", "B1", "B2"), hm(6, 0), hm(22, 0), 300)
        .transfer("X", "Xb", 60)
        .build(fares)

    private fun slabs(line: String = "", vararg kmFare: Pair<Double, Int>, day: FareDay = FareDay.All) =
        kmFare.map { (km, rupees) -> FareRule(SlabUnit.Km, km, rupees * 100, day, line) }

    private fun quote(fares: FareData, from: String, to: String, date: LocalDate = MONDAY, at: Int = hm(9, 0)): FareQuote? {
        val net = network(fares)
        val journey = JourneyPlanner(net).plan(from, to, date, at, departures = 1).journeys.first()
        return FareEngine(net).quote(journey, date, journey.departSecs)
    }

    @Test
    fun `distance slab uses track distance`() {
        val fares = FareData(emptyMap(), slabs("", 2.0 to 10, 5.0 to 20, 1e9 to 30), emptyList(), emptyList())
        val q = quote(fares, "A1", "X")!!
        assertEquals(2000, q.totalPaise) // 3 km
        assertEquals(FareBasis.Distance, q.components.single().basis)
        assertEquals(3.0, q.components.single().measure!!, 0.05)
        // One network ticket across the change: 3 km on A plus 1 km on B.
        val across = quote(fares, "A1", "B1")!!
        assertEquals(1, across.components.size)
        assertEquals(2000, across.totalPaise)
    }

    @Test
    fun `station count slabs`() {
        val fares = FareData(emptyMap(), listOf(FareRule(SlabUnit.Stations, 2.0, 1000, FareDay.All, ""), FareRule(SlabUnit.Stations, 1e9, 2500, FareDay.All, "")), emptyList(), emptyList())
        assertEquals(1000, quote(fares, "A1", "A3")!!.totalPaise)
        assertEquals(2500, quote(fares, "A1", "X")!!.totalPaise)
    }

    @Test
    fun `sunday uses holiday slabs`() {
        val fares = FareData(emptyMap(), slabs("", 1e9 to 30, day = FareDay.Weekday) + slabs("", 1e9 to 20, day = FareDay.Holiday), emptyList(), emptyList())
        assertEquals(3000, quote(fares, "A1", "X", MONDAY)!!.totalPaise)
        assertEquals(2000, quote(fares, "A1", "X", SUNDAY)!!.totalPaise)
    }

    @Test
    fun `separate operators need separate tickets, identical slabs share one`() {
        val separate = FareData(emptyMap(), slabs("A", 1e9 to 30) + slabs("B", 1e9 to 40), emptyList(), emptyList())
        val q = quote(separate, "A1", "B2")!!
        assertEquals(2, q.components.size)
        assertEquals(7000, q.totalPaise)

        val shared = FareData(emptyMap(), slabs("A", 1e9 to 30) + slabs("B", 1e9 to 30), emptyList(), emptyList())
        assertEquals(3000, quote(shared, "A1", "B2")!!.totalPaise)
    }

    @Test
    fun `unknown fares for a line leave the total unknown`() {
        val fares = FareData(emptyMap(), slabs("A", 1e9 to 30), emptyList(), emptyList())
        val q = quote(fares, "A1", "B2")!!
        assertNull(q.totalPaise)
        assertEquals(3000, q.components[0].paise)
        assertNull(q.components[1].paise)
        assertTrue(q.media.isEmpty())
    }

    @Test
    fun `origin-destination table, trying the other platforms of an interchange`() {
        val fares = FareData(mapOf("A1" to mapOf("Xb" to 4200)), emptyList(), emptyList(), emptyList())
        val q = quote(fares, "A1", "X")!!
        assertEquals(4200, q.totalPaise)
        assertEquals(FareBasis.Table, q.components.single().basis)
    }

    @Test
    fun `media discounts by time of day and day of week`() {
        val discounts = listOf(
            FareDiscount("smart_card", Timeframe.Peak, FareDay.Weekday, "", 10.0, "peak"),
            FareDiscount("smart_card", Timeframe.OffPeak, FareDay.Weekday, "", 20.0, "off-peak"),
            FareDiscount("smart_card", Timeframe.All, FareDay.Holiday, "", 10.0, "sunday"),
        )
        val fares = FareData(emptyMap(), slabs("", 1e9 to 50), discounts, listOf(PeakWindow(0b0111111, hm(8, 0), hm(12, 0))))
        fun card(date: LocalDate, at: Int) = quote(fares, "A1", "X", date, at)!!.media.single { it.media == "smart_card" }
        assertEquals(4500, card(MONDAY, hm(9, 0)).paise)
        assertEquals(4000, card(MONDAY, hm(13, 0)).paise)
        assertEquals(4500, card(SUNDAY, hm(9, 0)).paise)
    }

    @Test
    fun `line-specific discount applies only to that operator's ticket`() {
        val fares = FareData(
            emptyMap(), slabs("A", 1e9 to 30) + slabs("B", 1e9 to 40),
            listOf(FareDiscount("smart_card", Timeframe.All, FareDay.All, "B", 10.0, "B only")), emptyList(),
        )
        val card = quote(fares, "A1", "B2")!!.media.single()
        assertEquals(3000 + 3600, card.paise)
    }
}
