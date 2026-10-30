package io.github.arnavdugad.metropulse.core.designsystem.component

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/** Semantic haptics so every screen uses the same feel for the same kind of action. */
@Stable
class MpHaptics internal constructor(private val view: View) {
    /** Light tick for selections, toggles and snapping. */
    fun tick() = perform(if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)

    /** A primary action was accepted (start trip, download started). */
    fun confirm() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)

    /** Something was refused or failed. */
    fun reject() = perform(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberMpHaptics(): MpHaptics {
    val view = LocalView.current
    return remember(view) { MpHaptics(view) }
}
