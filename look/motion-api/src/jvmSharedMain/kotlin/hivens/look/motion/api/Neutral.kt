package hivens.look.motion.api

import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.unit.dp

/**
 * The movement of the base answers: what Compose itself does where nobody chose, so a window drawn with
 * no motion provider moves as no look in particular. One length and one curve for every role, Compose's
 * default tween, and a fade for every coming and going, with no travel and no growth.
 */
internal object Neutral {
    /** Every role that starts and ends: Compose's default tween, 300 ms on its standard curve. */
    val ROLE: MotionRole = MotionRole.tween(AnimationConstants.DefaultDurationMillis, FastOutSlowInEasing)

    /** A loop is linear, since an eased loop visibly stops at its seam. */
    val LOOP_EASING = LinearEasing

    /** A sweep runs a plain second per period. */
    val SWEEP: MotionLoop = MotionLoop.tween(1_000, LOOP_EASING)

    /** Every arrival a plain fade. */
    val ARRIVAL: Arrival = Arrival(ROLE, 0.dp, 1f)
}
