package io.github.arnavdugad.metropulse.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsTest {

    @get:Rule val folder = TemporaryFolder()

    private fun TestScope.settings() = AppSettings(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("settings.preferences_pb") },
    )

    @Test
    fun `defaults until changed`() = runTest(UnconfinedTestDispatcher()) {
        val s = settings()
        assertEquals(Settings(), s.current())
        s.setTheme(ThemeMode.Dark)
        s.setAlertStops(5)
        s.setOnboardingDone()
        val now = s.current()
        assertEquals(ThemeMode.Dark, now.theme)
        assertEquals(2, now.alertStops)
        assertEquals(true, now.onboardingDone)
        assertFalse(now.highContrast)
    }

    @Test
    fun `commute needs two different stations in the same city`() = runTest(UnconfinedTestDispatcher()) {
        val s = settings()
        s.setCommuteStation("bengaluru", home = true, complexId = "WHTM")
        assertNull(s.commute("bengaluru").first())
        s.setCommuteStation("bengaluru", home = false, complexId = "WHTM")
        assertNull(s.commute("bengaluru").first())
        s.setCommuteStation("bengaluru", home = false, complexId = "KGWA")
        val commute = s.commute("bengaluru").first()!!
        assertNull(s.commute("delhi").first())
        assertEquals("WHTM" to "KGWA", commute.leg(hour = 8))
        assertEquals("KGWA" to "WHTM", commute.leg(hour = 18))
    }
}
