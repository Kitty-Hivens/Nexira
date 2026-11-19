package hivens.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import hivens.look.motion.api.MotionAnswers
import hivens.widget.model.Entrance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * nx's motion answers every role exactly as nx-ui moved before the motion axis, with reduce motion off
 * and on: the same length, curve and delay, the same coming and going, and, for the movement the
 * components held still themselves when the person asked for less movement, the same choice. The
 * values on the "before" side are the ones the old `Motion` object and the call sites spelled, copied
 * here as they were written.
 */
class NxMotionTest {

    // The old object's curves.
    private val standard: Easing = FastOutSlowInEasing
    private val open: Easing = CubicBezierEasing(0.16f, 0.84f, 0.28f, 1f)
    private val arrive: Easing = CubicBezierEasing(0.15f, 1.4f, 0.64f, 0.96f)

    /** One role as the old object made it: a length, a curve and its pair of transitions. */
    private class Before(val ms: Int, val easing: Easing, val enter: (TransformOrigin) -> EnterTransition, val exit: (TransformOrigin) -> ExitTransition)

    private fun fading(ms: Int, easing: Easing) = Before(ms, easing, { fadeIn(tween(ms, easing = easing)) }, { fadeOut(tween(ms, easing = easing)) })

    private fun sliding(ms: Int) = Before(
        ms, standard,
        { slideInVertically(tween(ms, easing = standard)) { h -> h / 4 } + fadeIn(tween(ms, easing = standard)) },
        { slideOutVertically(tween(ms, easing = standard)) { h -> h / 4 } + fadeOut(tween(ms, easing = standard)) },
    )

    private val tapBefore = fading(110, standard)
    private val revealBefore = Before(
        380, open,
        { expandVertically(tween(380, easing = open)) + fadeIn(tween(380, easing = open)) },
        { shrinkVertically(tween(380, easing = open)) + fadeOut(tween(380, easing = open)) },
    )
    private val emphasisBefore = Before(
        260, arrive,
        { origin -> scaleIn(tween(260, easing = arrive), initialScale = 0.92f, transformOrigin = origin) + fadeIn(tween(260, easing = arrive)) },
        { origin -> scaleOut(tween(260, easing = arrive), targetScale = 0.92f, transformOrigin = origin) + fadeOut(tween(260, easing = arrive)) },
    )

    /** Every role that moves either way, by its name on the axis, against what moved for it before. */
    private fun always(): Map<String, Pair<(MotionAnswers) -> MotionRole, Before>> = mapOf(
        "tap" to Pair({ a -> a.tap }, tapBefore),
        "fade" to Pair({ a -> a.fade }, fading(200, standard)),
        "colourShift" to Pair({ a -> a.colourShift }, fading(250, standard)),
        "track" to Pair({ a -> a.track }, fading(250, standard)),
        "panelSlide" to Pair({ a -> a.panelSlide }, sliding(280)),
        "reveal" to Pair({ a -> a.reveal }, revealBefore),
        "emphasis" to Pair({ a -> a.emphasis }, emphasisBefore),
        // The menu popup of NxContextMenu, NxChoiceMenu and NxSelect, spelled at its call site.
        "popup" to Pair(
            { a -> a.popup },
            Before(
                380, open,
                { origin -> fadeIn(tween(110, easing = standard)) + scaleIn(tween(380, easing = open), initialScale = 0.86f, transformOrigin = origin) },
                { origin -> fadeOut(tween(110, easing = standard)) + scaleOut(tween(110, easing = standard), targetScale = 0.92f, transformOrigin = origin) },
            ),
        ),
        // NxSwap's default role, the fade.
        "pageSwap" to Pair({ a -> a.pageSwap }, fading(200, standard)),
        // The tab row scrolled on the track role.
        "scroll" to Pair({ a -> a.scroll }, fading(250, standard)),
        // A notification swiped away on the panel slide.
        "dismiss" to Pair({ a -> a.dismiss }, sliding(280)),
    )

    @Test
    fun `every role nx moved by moves the same, with reduce motion off and on`() {
        val origins = listOf(TransformOrigin.Center, TransformOrigin(0f, 0f), TransformOrigin(1f, 0.35f))
        listOf(false, true).forEach { reduce ->
            val answers = nxMotionAnswers(reduce)
            always().forEach { (name, pair) ->
                val (read, before) = pair
                val role = read(answers)
                val case = "$name, reduce motion $reduce"
                assertEquals(before.ms, role.durationMs, case)
                assertEquals(tween<Float>(before.ms, easing = before.easing), role.of<Float>(), case)
                listOf(0, 55, 110, 600).forEach { delay ->
                    assertEquals(tween<Float>(before.ms, delay, before.easing), role.of<Float>(delayMs = delay), "$case, delayed $delay")
                }
                origins.forEach { origin ->
                    assertEquals(described(before.enter(origin)), described(role.enter(origin)), "$case, entering about $origin")
                    assertEquals(described(before.exit(origin)), described(role.exit(origin)), "$case, leaving about $origin")
                }
                assertEquals(described(before.enter(TransformOrigin.Center)), described(role.enter), case)
                assertEquals(described(before.exit(TransformOrigin.Center)), described(role.exit), case)
            }
            assertEquals(tween<Float>(1_200, easing = LinearEasing), answers.sweep.of<Float>(), "sweep, reduce motion $reduce")
            assertEquals(1_200, answers.sweep.periodMs)
            listOf(550, 900, 950, 1_100, 1_600, 3_200).forEach { period ->
                // ownRhythm(period): the period on the standard curve, the shimmer's 700 ms rest before it.
                assertEquals(tween<Float>(period, easing = standard), answers.ambient(period).of<Float>(), "ambient $period, reduce motion $reduce")
                assertEquals(tween<Float>(period, 700, standard), answers.ambient(period).of<Float>(gapMs = 700))
            }
        }
    }

    @Test
    fun `with reduce motion off, what only decorates moves as the components moved it`() {
        val answers = nxMotionAnswers(reduceMotion = false)
        assertEquals(tween<Float>(550, easing = FastOutSlowInEasing), answers.lookChange?.of<Float>(), "the theme wipe, 550 ms on the standard curve")
        assertEquals(tween<Float>(800, easing = standard), answers.drift?.of<Float>(), "drift moves")
        listOf(answers.tap, answers.reveal, answers.panelSlide, answers.emphasis, answers.fade).forEach {
            assertSame(it, answers.decoration(it), "a row's hover, a row's height, a sheet's travel, a lock, a section: each as it was")
        }
        // The stagger WidgetEntrance spelled: 70 ms a place, six places at most.
        (0..40).forEach { order -> assertEquals(order.coerceIn(0, 6) * 70, answers.stagger(order), "place $order") }
    }

    @Test
    fun `with reduce motion on, what the components held still is held still, and the rest moves`() {
        val answers = nxMotionAnswers(reduceMotion = true)
        assertNull(answers.lookChange, "the day and night switch flipped with no wipe")
        assertNull(answers.drift, "particles, the skin's spin and screen effects held their first frame")
        listOf(answers.tap, answers.reveal, answers.panelSlide, answers.emphasis, answers.fade).forEach {
            assertNull(answers.decoration(it), "NxRow, NxSettingRow, NxSideSheet, PackLockCard and the settings sheet's sections drew at once")
        }
        (0..40).forEach { order -> assertEquals(0, answers.stagger(order), "a slot's order is held still") }
    }

    @Test
    fun `an arrival means what WidgetEntrance drew for it, with reduce motion off and on`() {
        listOf(false, true).forEach { reduce ->
            val answers = nxMotionAnswers(reduce)
            assertNull(answers.arrival(Entrance.None))
            val fade = requireNotNull(answers.arrival(Entrance.Fade))
            assertEquals(tween<Float>(200, easing = standard), fade.role.of<Float>())
            assertEquals(0.dp, fade.travel)
            assertEquals(1f, fade.fromScale)
            val rise = requireNotNull(answers.arrival(Entrance.Rise))
            assertEquals(tween<Float>(380, easing = open), rise.role.of<Float>())
            assertEquals(18.dp, rise.travel)
            assertEquals(1f, rise.fromScale)
            val settle = requireNotNull(answers.arrival(Entrance.Settle))
            assertEquals(tween<Float>(260, easing = arrive), settle.role.of<Float>())
            assertEquals(0.dp, settle.travel)
            assertEquals(0.94f, settle.fromScale)
        }
    }

    @Test
    fun `the answers are made once, so a role is the same value wherever it is read`() {
        assertSame(nxMotionAnswers(false), nxMotionAnswers(false))
        assertSame(nxMotionAnswers(true), nxMotionAnswers(true))
        assertSame(nxMotionAnswers(false).tap, nxMotionAnswers(true).tap)
        assertTrue(MotionAnswers.BASE.tap.durationMs != nxMotionAnswers(false).tap.durationMs, "the base is not nx")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a component drawn in an nx theme reads nx's motion through the old names`() {
        val seen = LinkedHashMap<String, Any?>()
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            NxThemed(NeutralTheme) {
                seen["tap"] = Motion.tap
                seen["colorShift"] = Motion.colorShift
                seen["drift"] = Motion.drift
            }
            NxThemed(NeutralTheme, reduceMotion = true) {
                seen["held drift"] = Motion.drift
            }
            seen["outside"] = Motion.tap
        }
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        val nx = nxMotionAnswers(false)
        assertSame(nx.tap, seen["tap"])
        assertSame(nx.colourShift, seen["colorShift"])
        assertSame(nx.drift, seen["drift"])
        assertNull(seen["held drift"])
        assertSame(MotionAnswers.BASE.tap, seen["outside"], "outside any look the base answers")
    }

    /**
     * What [transition] does, as values that compare: its parts as Compose holds them, with a slide's
     * and a size change's functions asked over one size, since two lambdas that do the same are never
     * equal.
     */
    private fun described(transition: Any): List<Any?> {
        // Read through the public class that declares it, since the instance's own class is not public.
        val declaring = if (transition is EnterTransition) EnterTransition::class.java else ExitTransition::class.java
        val data = declaring.getMethod("getConfig").invoke(transition)
        return data.javaClass.declaredFields.sortedBy { it.name }.map { field ->
            field.isAccessible = true
            field.name to part(field.get(data))
        }
    }

    @Suppress("UNCHECKED_CAST")
    // The functions a slide and a size change carry take a size, as Compose declares them.
    private fun part(value: Any?): Any? {
        val size = IntSize(400, 200)
        return when (value?.javaClass?.simpleName) {
            "SlideConfig" -> {
                val offset = value.javaClass.getMethod("getSlideOffset").invoke(value) as (IntSize) -> Any
                listOf(offset(size), value.javaClass.getMethod("getAnimationSpec").invoke(value))
            }
            "ChangeSizeConfig" -> {
                val resize = value.javaClass.getMethod("getSize").invoke(value) as (IntSize) -> Any
                listOf(
                    resize(size),
                    value.javaClass.getMethod("getAlignment").invoke(value),
                    value.javaClass.getMethod("getAnimationSpec").invoke(value),
                    value.javaClass.getMethod("getClip").invoke(value),
                )
            }
            else -> value
        }
    }
}
