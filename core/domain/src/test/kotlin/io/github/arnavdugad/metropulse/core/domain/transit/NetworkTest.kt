package io.github.arnavdugad.metropulse.core.domain.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkTest {

    @Test
    fun `operating hours fall back to the timetable when none are published`() {
        val network = TestNetworkBuilder()
            .station("P", 0.0, 0.0).station("Q", 1.0, 0.0, complex = "Q").station("Q2", 1.0, 0.01, complex = "Q")
            .service("weekdays", 0b0011111)
            .route("A", listOf("P", "Q"), hm(5, 30), hm(23, 0), 1800, serviceId = "weekdays")
            .untimedRoute("U", listOf("Q2", "P"))
            .build()
        val hours = network.operatingHours("A", MONDAY)!!
        assertEquals(hm(5, 30) to hm(23, 0), hours.firstSecs to hours.lastSecs)
        assertNull("no weekend service", network.operatingHours("A", SUNDAY))
        assertNull("untimed line without published hours", network.operatingHours("U", MONDAY))
        assertEquals(listOf("A", "U"), network.linesAtComplex.getValue("Q").map { it.id })
    }
}
