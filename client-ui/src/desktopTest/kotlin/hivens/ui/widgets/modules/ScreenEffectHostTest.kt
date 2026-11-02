package hivens.ui.widgets.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.ui.widgets.modules.WidgetModules.ModuleEffect
import hivens.widget.api.ModuleSetting
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ScreenEffectState
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which effects, if any, the interface is drawn through, and that switching one does not rebuild the interface. */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenEffectHostTest {

    private class Spy(override val id: String, val order: MutableList<String> = mutableListOf()) : ScreenEffect {
        override val label = id
        override val settings = listOf<ModuleSetting>(ModuleSetting.Amount("strength", "strength", default = 0.5f))
        val seen = mutableListOf<ScreenEffectState>()

        @Composable
        override fun Apply(state: ScreenEffectState, content: @Composable () -> Unit) {
            seen += state
            order += id
            content()
        }
    }

    private fun entry(effect: ScreenEffect, module: String = "m") =
        ModuleEffect(module, module, effect, ModuleStrings.EMPTY)

    private fun on(vararg scopes: String): Map<String, Map<String, String>> =
        scopes.associateWith { mapOf(WidgetModules.EFFECT_ENABLED to "true") }

    private class Harness(val effects: List<ModuleEffect>, settings: Map<String, Map<String, String>>, reduced: Boolean = false) {
        var settings by mutableStateOf(settings)
        var reduced by mutableStateOf(reduced)
        val tokens = mutableListOf<Any>()
        private lateinit var scene: ImageComposeScene

        fun open() {
            scene = ImageComposeScene(50, 50, density = Density(1f)) {
                ScreenEffectHost(effects, this@Harness.settings, this@Harness.reduced) {
                    // One object kept for as long as this composition lives.
                    tokens += remember { Any() }
                }
            }
            settle()
        }

        fun settle() = repeat(3) { scene.render() }

        fun close() = scene.close()
    }

    private fun run(h: Harness, body: Harness.() -> Unit) = SwingUtilities.invokeAndWait {
        h.open()
        try {
            h.body()
        } finally {
            h.close()
        }
    }

    @Test
    fun `nothing switched on draws the interface bare`() {
        val spy = Spy("a")
        run(Harness(listOf(entry(spy)), emptyMap())) {
            assertTrue(spy.seen.isEmpty(), "an effect nobody switched on was applied")
            assertTrue(tokens.isNotEmpty(), "the content was not drawn")
        }
    }

    @Test
    fun `an effect is handed its own values and the motion setting`() {
        val spy = Spy("a")
        val settings = on("m/a") + ("m/a" to mapOf(WidgetModules.EFFECT_ENABLED to "true", "strength" to "0.8"))
        run(Harness(listOf(entry(Spy("b")), entry(spy)), settings, reduced = true)) {
            val state = spy.seen.last()
            assertEquals(0.8f, state.values.amount("strength"))
            assertTrue(state.reducedMotion)
        }
    }

    @Test
    fun `a value outside the declared range is held to it`() {
        val spy = Spy("a")
        run(Harness(listOf(entry(spy)), mapOf("m/a" to mapOf(WidgetModules.EFFECT_ENABLED to "true", "strength" to "7")))) {
            assertEquals(1f, spy.seen.last().values.amount("strength"))
        }
    }

    @Test
    fun `a value that does not read is the declared default`() {
        val spy = Spy("a")
        run(Harness(listOf(entry(spy)), mapOf("m/a" to mapOf(WidgetModules.EFFECT_ENABLED to "true", "strength" to "lots")))) {
            assertEquals(0.5f, spy.seen.last().values.amount("strength"))
        }
    }

    @Test
    fun `a switch kept for a module that is gone draws the interface bare`() {
        val spy = Spy("a")
        run(Harness(listOf(entry(spy)), on("someone-else/a"))) {
            assertTrue(spy.seen.isEmpty())
            assertTrue(tokens.isNotEmpty())
        }
    }

    @Test
    fun `two modules may use the same effect id and are told apart`() {
        val first = Spy("fx")
        val second = Spy("fx")
        run(Harness(listOf(entry(first, "one"), entry(second, "two")), on("two/fx"))) {
            assertTrue(first.seen.isEmpty(), "the other module's effect was applied")
            assertTrue(second.seen.isNotEmpty())
        }
    }

    @Test
    fun `effects that are on are drawn one inside the next in load order`() {
        val order = mutableListOf<String>()
        val a = Spy("a", order)
        val b = Spy("b", order)
        run(Harness(listOf(entry(a), entry(b)), on("m/a", "m/b"))) {
            assertEquals(listOf("a", "b"), order.distinct())
        }
    }

    @Test
    fun `switching an effect on and off keeps the interface it draws`() {
        val spy = Spy("a")
        run(Harness(listOf(entry(spy)), emptyMap())) {
            val first = tokens.last()
            settings = on("m/a")
            settle()
            assertTrue(spy.seen.isNotEmpty(), "the effect was not applied")
            assertSame(first, tokens.last(), "the interface was rebuilt when the effect came on")
            settings = emptyMap()
            settle()
            assertSame(first, tokens.last(), "the interface was rebuilt when the effect went off")
        }
    }

    @Test
    fun `switching a second effect on leaves the first one's state alone`() {
        val a = Spy("a")
        val b = Spy("b")
        run(Harness(listOf(entry(a), entry(b)), on("m/a"))) {
            val first = tokens.last()
            settings = on("m/a", "m/b")
            settle()
            assertSame(first, tokens.last(), "the interface was rebuilt when another effect came on")
            assertTrue(b.seen.isNotEmpty())
        }
    }
}
