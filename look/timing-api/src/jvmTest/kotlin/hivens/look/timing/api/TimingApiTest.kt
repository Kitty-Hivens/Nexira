package hivens.look.timing.api

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TimingApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookTimingApi")) { "the build did not pass nexira.lookTimingApi" }
        assertEquals(TimingApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the timing axis, answered in its own type with the base answers where none answers`() {
        assertEquals(listOf<Any>(Timing), TimingModule.contributions)
        assertEquals("timing", Timing.id)
        assertEquals(TimingApi.VERSION, Timing.contract)
        assertEquals(TimingAnswers::class, Timing.type)
        assertSame(TimingAnswers.BASE, Timing.base)
        assertEquals(mapOf(TimingApi.ID to TimingApi.VERSION), TimingModule.provides)
        assertEquals(mapOf(TimingApi.ID to setOf("hivens.look.timing.api")), TimingModule.apiPackages)
        assertTrue(TimingModule.strings.tables.values.all { Timing.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every role of the answers is filled by the builder and asked through Timing`() {
        fun members(type: Class<*>) = type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name.removePrefix("get").removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
            .toSet()
        assertEquals(setOf("tooltipDelay"), members(TimingAnswers::class.java))
        assertEquals(setOf("tooltipDelay"), members(TimingAnswers.Builder::class.java))
        assertEquals(setOf("tooltipDelay", "label"), members(Timing::class.java))
    }

    @Test
    fun `the base waits are Compose's own, and a provider's are its own`() {
        TooltipKind.entries.forEach { assertEquals(500, TimingAnswers.BASE.tooltipDelay(it)) }
        assertEquals(500, TimingAnswers.build {}.tooltipDelay(TooltipKind.Preview))
        val own = TimingAnswers.build { tooltipDelay = { if (it == TooltipKind.Label) 100 else 900 } }
        assertEquals(100, own.tooltipDelay(TooltipKind.Label))
        assertEquals(900, own.tooltipDelay(TooltipKind.Explain))
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = TimingAnswers.build { tooltipDelay = { 42 } }
        val seen = HashMap<String, Int>()
        composeOnce {
            seen["outside"] = Timing.tooltipDelay(TooltipKind.Label)
            CompositionLocalProvider(Timing.local provides own) { seen["inside"] = Timing.tooltipDelay(TooltipKind.Label) }
        }
        assertEquals(mapOf("outside" to 500, "inside" to 42), seen)
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
