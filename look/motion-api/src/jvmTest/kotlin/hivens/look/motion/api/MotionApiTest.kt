package hivens.look.motion.api

import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import hivens.widget.model.ApiVersion
import hivens.widget.model.Entrance
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MotionApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookMotionApi")) { "the build did not pass nexira.lookMotionApi" }
        assertEquals(MotionApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the motion axis, answered in its own type with the base answers where none answers`() {
        assertEquals(listOf<Any>(Motion), MotionModule.contributions)
        assertEquals("motion", Motion.id)
        assertEquals(MotionApi.VERSION, Motion.contract)
        assertEquals(MotionAnswers::class, Motion.type)
        assertSame(MotionAnswers.BASE, Motion.base)
        assertEquals(mapOf(MotionApi.ID to MotionApi.VERSION), MotionModule.provides)
        assertEquals(mapOf(MotionApi.ID to setOf("hivens.look.motion.api")), MotionModule.apiPackages)
        assertTrue(MotionModule.strings.tables.values.all { Motion.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every role of the answers is listed here, and a provider can fill every one`() {
        val roles = publicMembers(MotionAnswers::class.java)
        assertEquals(roles, sample(MotionAnswers.BASE).keys, "a role this test does not list goes unchecked below")
        assertEquals(roles, fillable(), "a role a provider cannot fill can only ever hold its base answer")
        assertEquals(roles - "ambient" - "arrival" - "stagger" - "decoration", accessors(), "every role is asked through Motion")
    }

    @Test
    fun `the base answers are Compose's own tween, coming and going on a fade, and loops are linear`() {
        val plain = tween<Float>(AnimationConstants.DefaultDurationMillis, easing = FastOutSlowInEasing)
        val base = MotionAnswers.BASE
        val roles = listOf(
            base.tap, base.colourShift, base.track, base.fade, base.popup, base.panelSlide, base.reveal, base.emphasis,
            base.pageSwap, base.scroll, base.dismiss, base.lookChange, base.drift,
        )
        roles.forEach { role ->
            assertNotNull(role, "the base holds nothing still")
            assertEquals(300, role.durationMs)
            assertEquals(plain, role.of<Float>())
            assertEquals(fadeIn(plain), role.enter)
            assertEquals(fadeOut(plain), role.exit)
            assertEquals(fadeIn(plain), role.enter(TransformOrigin(0f, 0f)), "a fade does not grow, so the origin changes nothing")
        }
        assertEquals(tween<Float>(1_000, easing = LinearEasing), base.sweep.of<Float>())
        assertEquals(tween<Float>(950, 700, LinearEasing), base.ambient(950).of<Float>(gapMs = 700))
        Entrance.entries.filter { it != Entrance.None }.forEach { entrance ->
            assertEquals(Arrival(base.fade, 0.dp, 1f), base.arrival(entrance), "$entrance is a plain fade")
        }
        assertNull(base.arrival(Entrance.None), "None arrives by nothing")
        (0..20).forEach { assertEquals(0, base.stagger(it), "siblings arrive together") }
        assertSame(base.tap, base.decoration(base.tap), "decoration plays")
    }

    @Test
    fun `nothing filled is the base answers, role by role`() {
        assertEquals(sample(MotionAnswers.BASE), sample(MotionAnswers.build {}))
    }

    @Test
    fun `a role a provider fills is its own, one it holds still is null, and the rest keep their base`() {
        val quick = MotionRole.tween(90, LinearEasing)
        val landing = MotionRole.tween(260, CubicBezierEasing(0.15f, 1.4f, 0.64f, 0.96f), enter = { role, origin -> scaleIn(role.of(), 0.5f, origin) })
        val answers = MotionAnswers.build {
            tap = quick
            emphasis = landing
            lookChange = { null }
            stagger = { it * 10 }
            decoration = { null }
            arrival = { Arrival(landing, 4.dp, 0.9f) }
        }
        assertSame(quick, answers.tap)
        assertEquals(scaleIn(landing.of(), 0.5f, TransformOrigin(0f, 1f)), answers.emphasis.enter(TransformOrigin(0f, 1f)))
        assertNull(answers.lookChange)
        assertNull(answers.decoration(quick))
        assertEquals(30, answers.stagger(3))
        assertEquals(Arrival(landing, 4.dp, 0.9f), answers.arrival(Entrance.Rise))
        assertNull(answers.arrival(Entrance.None), "None is never asked of a provider")
        assertSame(MotionAnswers.BASE.fade, answers.fade)
        assertSame(MotionAnswers.BASE.drift, answers.drift)
    }

    @Test
    fun `a role stands for its own spec wherever a float spec is asked`() {
        val role = MotionRole.tween(110, FastOutSlowInEasing)
        assertEquals(tween<Float>(110, 40, FastOutSlowInEasing), role.of<Float>(delayMs = 40))
        val converter = Float.VectorConverter
        val from = AnimationVector1D(0f)
        val to = AnimationVector1D(1f)
        val spec = tween<Float>(110, easing = FastOutSlowInEasing).vectorize(converter)
        val drawn = role.vectorize(converter)
        assertEquals(spec.getDurationNanos(from, to, from), drawn.getDurationNanos(from, to, from))
        listOf(0L, 30L, 55L, 90L, 110L).map { it * 1_000_000 }.forEach { nanos ->
            assertEquals(spec.getValueFromNanos(nanos, from, to, from), drawn.getValueFromNanos(nanos, from, to, from))
        }
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = MotionAnswers.build { tap = MotionRole.tween(90, LinearEasing) }
        val seen = HashMap<String, Any?>()
        composeOnce {
            seen["outside"] = Motion.tap
            seen["stagger"] = Motion.stagger(4)
            CompositionLocalProvider(Motion.local provides own) {
                seen["inside"] = Motion.tap
                seen["fade"] = Motion.fade
            }
        }
        assertSame(MotionAnswers.BASE.tap, seen["outside"])
        assertEquals(0, seen["stagger"])
        assertSame(own.tap, seen["inside"])
        assertSame(own.fade, seen["fade"])
    }

    /** The names of [type]'s public members, a getter by its property's name, with the suffix a value class parameter adds cut off. */
    private fun publicMembers(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .map { method ->
            val name = method.name.substringBefore('-')
            if (name.startsWith("get") && method.parameterCount == 0) name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) else name
        }
        .toSet()

    /** The roles the builder has a field for. */
    private fun fillable(): Set<String> = MotionAnswers.Builder::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("set") }
        .map { it.name.substringBefore('-').removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** The roles [Motion] asks as properties. */
    private fun accessors(): Set<String> = Motion::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("get") && it.name != "getLabel" }
        .map { it.name.substringBefore('-').removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** Every role of [answers] asked over a spread of what it is asked over, by role name, as values that compare. */
    private fun sample(answers: MotionAnswers): Map<String, List<Any?>> {
        fun MotionRole?.seen(): Any? = this?.let { listOf(it.durationMs, it.of<Float>(), it.of<Float>(30), it.enter, it.exit, it.enter(TransformOrigin(0f, 0f))) }
        val roles = listOf(answers.tap, answers.fade)
        return mapOf(
            "tap" to listOf(answers.tap.seen()),
            "colourShift" to listOf(answers.colourShift.seen()),
            "track" to listOf(answers.track.seen()),
            "fade" to listOf(answers.fade.seen()),
            "popup" to listOf(answers.popup.seen()),
            "panelSlide" to listOf(answers.panelSlide.seen()),
            "reveal" to listOf(answers.reveal.seen()),
            "emphasis" to listOf(answers.emphasis.seen()),
            "pageSwap" to listOf(answers.pageSwap.seen()),
            "scroll" to listOf(answers.scroll.seen()),
            "dismiss" to listOf(answers.dismiss.seen()),
            "lookChange" to listOf(answers.lookChange.seen()),
            "drift" to listOf(answers.drift.seen()),
            "sweep" to listOf(answers.sweep.periodMs, answers.sweep.of<Float>()),
            "ambient" to listOf(500, 950, 3200).map { answers.ambient(it).of<Float>(gapMs = 10) },
            "arrival" to Entrance.entries.map { answers.arrival(it)?.let { a -> listOf(a.role.seen(), a.travel, a.fromScale) } },
            "stagger" to (0..12).map(answers::stagger),
            "decoration" to roles.map { answers.decoration(it).seen() },
        )
    }
}

/** Composes [content] once, with nothing drawn, and disposes it. */
private fun composeOnce(content: @Composable () -> Unit) {
    val recomposer = Recomposer(Dispatchers.Unconfined)
    val composition = Composition(NothingApplier, recomposer)
    try {
        composition.setContent(content)
    } finally {
        composition.dispose()
        recomposer.cancel()
    }
}

private object NothingApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
