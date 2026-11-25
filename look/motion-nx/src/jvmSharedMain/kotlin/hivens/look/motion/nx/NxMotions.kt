package hivens.look.motion.nx

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.dp
import hivens.look.motion.api.Arrival
import hivens.look.motion.api.MotionAnswers
import hivens.look.motion.api.MotionLoop
import hivens.look.motion.api.MotionRole
import hivens.widget.model.Entrance

/**
 * nx's motion as the axis's answers: what the nx motion provider answers, and what previews and
 * tests draw in. With [reduceMotion] the movement that only decorates is held still:
 * decoration, the look's own change, ambient drift and the order siblings arrive in. Everything else
 * moves the same either way. Each of the two is made once, so a role is the same value wherever it is
 * read.
 */
fun nxMotionAnswers(reduceMotion: Boolean): MotionAnswers = if (reduceMotion) NxMotion.HELD else NxMotion.MOVING

/**
 * The nx scale. Deliberately few lengths, spaced far enough apart to be told apart on sight: the set it
 * replaced (90, 110, 120, 160, 170, 180, 200, 220, 250, 260, 300, 380, 500, 700, 950) had rungs no eye
 * could separate and no rule to choose between.
 */
private object NxMotion {
    private const val TAP_MS = 110
    private const val FADE_MS = 200
    private const val COLOR_MS = 250
    private const val SLIDE_MS = 280
    private const val REVEAL_MS = 380
    private const val EMPHASIS_MS = 260
    private const val DRIFT_MS = 800
    private const val SWEEP_MS = 1_200

    /** The theme wipe from the day and night switch. Long, because it crosses the whole window. */
    private const val LOOK_CHANGE_MS = 550

    /** The step between one widget and the next in a slot: a third of the fade it staggers. */
    private const val STAGGER_MS = 70

    /** Past this many places a slot stops staggering, so a long list does not trickle in. */
    private const val STAGGER_STEPS = 6

    /** Even acceleration and settle. The curve for movement with nothing to say. */
    private val Standard: Easing = FastOutSlowInEasing

    /** Opening: fast then settling, with no overshoot. */
    private val Open: Easing = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1f)

    /** Arrival: overshoots, the way something landing does. */
    private val Arrive: Easing = CubicBezierEasing(0.15f, 1.4f, 0.64f, 0.96f)

    private val tap = MotionRole.tween(TAP_MS, Standard)
    private val fade = MotionRole.tween(FADE_MS, Standard)
    private val colourShift = MotionRole.tween(COLOR_MS, Standard)

    // A mark travelling settles at the pace a selection's colour does, which is what it is, moved.
    private val track = MotionRole.tween(COLOR_MS, Standard)
    private val panelSlide = slide(SLIDE_MS)
    private val reveal = MotionRole.tween(
        REVEAL_MS, Open,
        enter = { role, _ -> expandVertically(role.of()) + fadeIn(role.of()) },
        exit = { role, _ -> shrinkVertically(role.of()) + fadeOut(role.of()) },
    )
    private val emphasis = MotionRole.tween(
        EMPHASIS_MS, Arrive,
        enter = { role, origin -> scaleIn(role.of(), initialScale = 0.92f, transformOrigin = origin) + fadeIn(role.of()) },
        exit = { role, origin -> scaleOut(role.of(), targetScale = 0.92f, transformOrigin = origin) + fadeOut(role.of()) },
    )

    // A menu fades on the press's pace and unfolds on the opening's, from where it was opened.
    private val popup = MotionRole.tween(
        REVEAL_MS, Open,
        enter = { role, origin -> fadeIn(tap.of()) + scaleIn(role.of(), initialScale = 0.86f, transformOrigin = origin) },
        exit = { _, origin -> fadeOut(tap.of()) + scaleOut(tap.of(), targetScale = 0.92f, transformOrigin = origin) },
    )
    private val pageSwap = MotionRole.tween(FADE_MS, Standard)
    private val scroll = MotionRole.tween(COLOR_MS, Standard)
    private val dismiss = slide(SLIDE_MS)
    private val lookChange = MotionRole.tween(LOOK_CHANGE_MS, Standard)
    private val drift = MotionRole.tween(DRIFT_MS, Standard)

    // Linear, because an eased loop visibly stutters at the seam.
    private val sweep = MotionLoop.tween(SWEEP_MS, LinearEasing)

    private val fading = Arrival(fade, 0.dp, 1f)

    // A rise is content opening, a short way into place: it settles there, it does not fly in.
    private val rise = Arrival(reveal, 18.dp, 1f)

    // A settle is something landing, the last few percent with its overshoot.
    private val settle = Arrival(emphasis, 0.dp, 0.94f)

    val MOVING: MotionAnswers = answers(reduceMotion = false)
    val HELD: MotionAnswers = answers(reduceMotion = true)

    private fun slide(ms: Int) = MotionRole.tween(
        ms, Standard,
        enter = { role, _ -> slideInVertically(role.of()) { h -> h / 4 } + fadeIn(role.of()) },
        exit = { role, _ -> slideOutVertically(role.of()) { h -> h / 4 } + fadeOut(role.of()) },
    )

    private fun answers(reduceMotion: Boolean): MotionAnswers = MotionAnswers.build {
        tap = NxMotion.tap
        fade = NxMotion.fade
        colourShift = NxMotion.colourShift
        track = NxMotion.track
        panelSlide = NxMotion.panelSlide
        reveal = NxMotion.reveal
        emphasis = NxMotion.emphasis
        popup = NxMotion.popup
        pageSwap = NxMotion.pageSwap
        scroll = NxMotion.scroll
        dismiss = NxMotion.dismiss
        sweep = NxMotion.sweep
        // A decorative period belongs to the effect, at the curve for movement with nothing to say.
        ambient = { periodMs -> MotionLoop.tween(periodMs, Standard) }
        arrival = { entrance ->
            when (entrance) {
                Entrance.Rise -> rise
                Entrance.Settle -> settle
                else -> fading
            }
        }
        if (reduceMotion) {
            lookChange = { null }
            drift = { null }
            stagger = { 0 }
            decoration = { null }
        } else {
            lookChange = { NxMotion.lookChange }
            drift = { NxMotion.drift }
            stagger = { order -> order.coerceIn(0, STAGGER_STEPS) * STAGGER_MS }
            decoration = { role -> role }
        }
    }
}
