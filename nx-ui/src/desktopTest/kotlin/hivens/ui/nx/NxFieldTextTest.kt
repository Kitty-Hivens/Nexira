package hivens.ui.nx

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the caller is told and what the field's text state then holds: typing reaches the caller, a
 * change the caller makes from outside lands in the state, and an edit the caller refuses is put
 * back. The state is edited the way the keyboard edits it, so no key event is faked.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NxFieldTextTest {

    private class Harness(initial: String, private val accept: (String) -> Boolean) {
        var text by mutableStateOf(initial)
        val reported = mutableListOf<String>()
        lateinit var state: TextFieldState
        private lateinit var scene: ImageComposeScene

        fun open() {
            scene = ImageComposeScene(100, 40, density = Density(1f)) {
                state = rememberFieldState(text) { reported += it; if (accept(it)) text = it }
            }
            settle()
        }

        fun type(typed: String) {
            state.edit { append(typed) }
            settle()
        }

        fun settle() = repeat(4) { scene.render() }

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
    fun `typing reaches the caller one edit at a time`() {
        run(Harness("") { true }) {
            type("a")
            type("b")
            assertEquals(listOf("a", "ab"), reported)
            assertEquals("ab", state.text.toString())
        }
    }

    @Test
    fun `a change made from outside lands in the state`() {
        run(Harness("") { true }) {
            text = "xyz"
            settle()
            assertEquals("xyz", state.text.toString())
            type("q")
            assertEquals(listOf("xyzq"), reported)
        }
    }

    @Test
    fun `an edit the caller refuses is put back`() {
        run(Harness("") { it.all(Char::isDigit) }) {
            type("a")
            assertEquals("", state.text.toString())
            type("7")
            assertEquals(listOf("a", "7"), reported)
            assertEquals("7", state.text.toString())
        }
    }

    @Test
    fun `the caller can rewrite what was typed`() {
        run(Harness("") { true }) {
            state.setTextAndPlaceCursorAtEnd("ab")
            settle()
            text = "AB"
            settle()
            assertEquals("AB", state.text.toString())
        }
    }
}
