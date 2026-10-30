package io.github.arnavdugad.metropulse.core.designsystem.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable

/**
 * Physics-based motion tokens. When the user turns on reduced motion every spec collapses to an
 * instant [snap], so callers never branch on the setting themselves.
 */
@Immutable
class MpMotion(val reduced: Boolean) {
    /** Default for layout and position changes: quick, no visible overshoot. */
    fun <T> standard(): AnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.9f, stiffness = 380f)

    /** For things that should feel alive (cards arriving, counters): a hint of bounce. */
    fun <T> bouncy(): AnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 0.72f, stiffness = 300f)

    /** Press feedback: fast and firm. */
    fun <T> press(): AnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)

    /** Slow settle for large surfaces such as sheets and hero transitions. */
    fun <T> gentle(): AnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 160f)
}
