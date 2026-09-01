package hivens.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DurationBasedAnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The one motion scale, beside [Spacing] and the shape tokens on [Form].
 *
 * A call site asks for what is happening -- a panel arriving, content opening, a
 * press answering -- and gets the duration and curve that belong to it. It does
 * not pick 220ms, because a number picked at a call site is a number nothing else
 * can reach: it cannot be scaled, compared with its neighbours, or found again.
 * The old set -- 90, 110, 120, 160, 170, 180, 200, 220, 250, 260, 300, 380, 500,
 * 700, 950 -- had rungs no eye could separate and no rule to choose between.
 *
 * There is no multiplier over the scale any more. There was one, on the style
 * axis, and it reached two call sites out of eighty: most of the interface
 * animated at whatever each site had hardcoded, which is the state this vocabulary
 * exists to end and has not ended yet.
 *
 * Reach for the nearest role. A genuinely new kind of movement is a new named
 * role here, not a literal at the call site.
 */
object Motion {

    /**
     * A press, a hover, a thumb sliding: the interface answering a finger. Short
     * enough to read as immediate rather than as an animation.
     */
    val tap: MotionRole
        @Composable @ReadOnlyComposable get() = role(TAP_MS, Standard)

    /** Something appearing or leaving on opacity alone -- the default crossfade. */
    val fade: MotionRole
        @Composable @ReadOnlyComposable get() = role(FADE_MS, Standard, Enter.fade, Exit.fade)

    /** Colour settling into a new state: theme, selection, validity. */
    val colorShift: MotionRole
        @Composable @ReadOnlyComposable get() = role(COLOR_MS, Standard)

    /** A panel, rail or sheet travelling in from its edge. */
    val panelSlide: MotionRole
        @Composable @ReadOnlyComposable get() = role(SLIDE_MS, Standard, Enter.slide, Exit.slide)

    /**
     * Content opening or folding away -- expanding rows, growing cards,
     * [androidx.compose.animation.animateContentSize]. Fast then settling, with no
     * overshoot to fight an arrival happening beside it.
     */
    val reveal: MotionRole
        @Composable @ReadOnlyComposable get() = role(REVEAL_MS, Open, Enter.reveal, Exit.reveal)

    /**
     * Something landing and claiming attention: a dialog, a notification, a face
     * joining the stack. Overshoots the way a landing object does.
     */
    val emphasis: MotionRole
        @Composable @ReadOnlyComposable get() = role(EMPHASIS_MS, Arrive, Enter.emphasis, Exit.emphasis)

    /**
     * Ambient movement nothing is waiting on -- parallax, backdrop drift. Long by
     * design: it should never read as a response to an action.
     */
    val drift: MotionRole
        @Composable @ReadOnlyComposable get() = role(DRIFT_MS, Standard)

    /**
     * Continuous motion with no start or finish -- a spinner, an indeterminate
     * bar. Linear, because an eased loop visibly stutters at the seam.
     */
    val sweep: MotionRole
        @Composable @ReadOnlyComposable get() = role(SWEEP_MS, LinearEasing)

    /**
     * A duration the vocabulary does not name.
     *
     * For decorative effects whose period belongs to the effect itself -- a
     * shimmer crossing a card, a glow breathing -- where the number is an artistic
     * choice rather than a role. Anything the interface does in answer to the user
     * is a role above, and reaching here for one of those is how the ad-hoc set
     * grew in the first place.
     */
    @Composable
    @ReadOnlyComposable
    fun ownRhythm(baseMs: Int, easing: Easing = Standard): MotionRole = role(baseMs, easing)

    @Composable
    @ReadOnlyComposable
    private fun role(
        baseMs: Int,
        easing: Easing,
        enter: (MotionRole) -> EnterTransition = Enter.fade,
        exit: (MotionRole) -> ExitTransition = Exit.fade,
    ): MotionRole = MotionRole(baseMs, easing, enter, exit)

    // Base durations. Deliberately few and spaced far
    // enough apart to be told apart on sight -- the old set (90, 110, 120, 160,
    // 170, 180, 200, 220, 250, 260, 300, 380, 500, 700, 950) had rungs no eye
    // could separate and no rule to choose between.
    private const val TAP_MS = 110
    private const val FADE_MS = 200
    private const val COLOR_MS = 250
    private const val SLIDE_MS = 280
    private const val REVEAL_MS = 380
    private const val EMPHASIS_MS = 260
    private const val DRIFT_MS = 800
    private const val SWEEP_MS = 1_200

    /** Even acceleration and settle. The curve for movement with nothing to say. */
    val Standard: Easing = FastOutSlowInEasing

    /** Opening: fast then settling, with no overshoot. */
    val Open: Easing = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1f)

    /** Arrival: overshoots, the way something landing does. */
    val Arrive: Easing = CubicBezierEasing(0.15f, 1.4f, 0.64f, 0.96f)

    private object Enter {
        val fade: (MotionRole) -> EnterTransition = { fadeIn(it.of()) }
        val slide: (MotionRole) -> EnterTransition = { slideInVertically(it.of()) { h -> h / 4 } + fadeIn(it.of()) }
        val reveal: (MotionRole) -> EnterTransition = { expandVertically(it.of()) + fadeIn(it.of()) }
        val emphasis: (MotionRole) -> EnterTransition = { scaleIn(it.of(), initialScale = 0.92f) + fadeIn(it.of()) }
    }

    private object Exit {
        val fade: (MotionRole) -> ExitTransition = { fadeOut(it.of()) }
        val slide: (MotionRole) -> ExitTransition = { slideOutVertically(it.of()) { h -> h / 4 } + fadeOut(it.of()) }
        val reveal: (MotionRole) -> ExitTransition = { shrinkVertically(it.of()) + fadeOut(it.of()) }
        val emphasis: (MotionRole) -> ExitTransition = { scaleOut(it.of(), targetScale = 0.92f) + fadeOut(it.of()) }
    }
}

/**
 * One resolved role: a duration, the curve that belongs to it, and the enter/exit
 * pair for visibility changes.
 *
 * Usable directly wherever a `FiniteAnimationSpec<Float>` is expected -- the
 * common case -- and through [of] for the typed variants (Dp, Color, IntOffset).
 */
@Immutable
class MotionRole internal constructor(
    val durationMs: Int,
    val easing: Easing,
    private val enterOf: (MotionRole) -> EnterTransition,
    private val exitOf: (MotionRole) -> ExitTransition,
) : FiniteAnimationSpec<Float> by tween(durationMs, easing = easing) {

    /**
     * The same role as a spec for any animatable type. Duration-based rather than
     * merely finite so it also feeds `infiniteRepeatable`, which a pulse or a
     * spinner needs.
     */
    fun <T> of(): DurationBasedAnimationSpec<T> = tween(durationMs, easing = easing)

    val enter: EnterTransition get() = enterOf(this)

    val exit: ExitTransition get() = exitOf(this)
}
