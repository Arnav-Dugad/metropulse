package io.github.arnavdugad.metropulse.baseline

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold start, then the paths riders use most: the map with its 3D city, station search and a
 * station's departures, planning a journey, and the lines. A fresh install goes through the
 * welcome tour first (choosing Bengaluru and skipping both permissions).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.finishWelcomeTour()

        device.wait(Until.hasObject(By.text("Where to?")), 15_000)
        device.waitForIdle()
        device.tap("Where to?")
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000)
        device.findObject(By.clazz("android.widget.EditText"))?.text = "majestic"
        device.wait(Until.hasObject(By.textContains("Majestic")), 5_000)
        device.findObjects(By.textContains("Majestic")).lastOrNull()?.click()
        device.wait(Until.hasObject(By.text("NEXT DEPARTURES")), 5_000)
        device.pressBack()

        device.tap("Plan")
        device.wait(Until.hasObject(By.text("From")), 5_000)
        device.tap("Lines")
        device.wait(Until.hasObject(By.text("Purple Line")), 5_000)
        device.tap("Purple Line")
        device.waitForIdle()
        device.pressBack()
        device.tap("Settings")
        device.waitForIdle()
    }

    private fun UiDevice.finishWelcomeTour() {
        if (!wait(Until.hasObject(By.text("Get started")), 3_000)) return
        tap("Get started")
        wait(Until.hasObject(By.text("Bengaluru")), 5_000)
        tap("Bengaluru")
        wait(Until.hasObject(By.text("Continue").enabled(true)), 10_000)
        tap("Continue")
        wait(Until.hasObject(By.text("Allow location")), 5_000)
        tap("Not now")
        // The pager slides: wait for the notifications page before tapping its "Not now".
        wait(Until.hasObject(By.text("Allow notifications")), 5_000)
        tap("Not now")
    }

    /** Taps [text], retrying while the screen is still animating (a sliding page goes stale). */
    private fun UiDevice.tap(text: String) {
        repeat(3) {
            try {
                findObject(By.text(text))?.click()
                waitForIdle()
                return
            } catch (_: StaleObjectException) {
                waitForIdle()
            }
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "io.github.arnavdugad.metropulse"
    }
}
