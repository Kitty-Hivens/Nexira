package hivens.ui.widgets.modules

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ScreenEffectState
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which effect, if any, the interface is drawn through, and that choosing one does not rebuild the interface. */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenEffectHostTest {

    private class Spy(override val id: String) : ScreenEffect {
        override val displayName = id
        val seen = mutableListOf<ScreenEffectState>()

        @Composable
        override fun Apply(state: ScreenEffectState, content: @Composable () -> Unit) {
            seen += state
            content()
        }
    }

    private class Harness(val effects: List<ScreenEffect>, selected: String?, intensity: Float = 0.5f, reduced: Boolean = false) {
        var selected by mutableStateOf(selected)
        var intensity by mutableStateOf(intensity)
        var reduced by mutableStateOf(reduced)
        val tokens = mutableListOf<Any>()
        private lateinit var scene: ImageComposeScene

        fun open() {
            scene = ImageComposeScene(50, 50, density = Density(1f)) {
                ScreenEffectHost(effects, this@Harness.selected, this@Harness.intensity, this@Harness.reduced) {
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
    fun `no choice draws the interface bare`() {
        val spy = Spy("a")
        run(Harness(listOf(spy), selected = null)) {
            assertTrue(spy.seen.isEmpty(), "an effect nobody chose was applied")
            assertTrue(tokens.isNotEmpty(), "the content was not drawn")
        }
    }

    @Test
    fun `the chosen effect is handed the person's strength and the motion setting`() {
        val spy = Spy("a")
        run(Harness(listOf(Spy("b"), spy), selected = "a", intensity = 0.8f, reduced = true)) {
            assertEquals(ScreenEffectState(0.8f, true), spy.seen.last())
        }
    }

    @Test
    fun `a strength outside the range is held to it`() {
        val spy = Spy("a")
        run(Harness(listOf(spy), selected = "a", intensity = 7f)) {
            assertEquals(1f, spy.seen.last().intensity)
        }
    }

    @Test
    fun `a choice whose module is gone draws the interface bare`() {
        val spy = Spy("a")
        run(Harness(listOf(spy), selected = "someone-else")) {
            assertTrue(spy.seen.isEmpty())
            assertTrue(tokens.isNotEmpty())
        }
    }

    @Test
    fun `choosing and dropping an effect keeps the interface it draws`() {
        val spy = Spy("a")
        run(Harness(listOf(spy), selected = null)) {
            val first = tokens.last()
            selected = "a"
            settle()
            assertTrue(spy.seen.isNotEmpty(), "the effect was not applied")
            assertSame(first, tokens.last(), "the interface was rebuilt when the effect came on")
            selected = null
            settle()
            assertSame(first, tokens.last(), "the interface was rebuilt when the effect went off")
        }
    }
}
