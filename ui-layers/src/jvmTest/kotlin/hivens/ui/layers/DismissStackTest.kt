package hivens.ui.layers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Escape and back through one window: a focused field of the host's first, then
 * whatever registered for back last, a Compose popup or dialog before the stack,
 * then the stack's top entry, and one thing per press.
 */
class DismissStackTest {

    @Test
    fun `Escape closes the most recent entry first`() {
        val layers = WindowLayers()
        var bOpen by mutableStateOf(false)
        val closed = mutableListOf<String>()
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed += "a" }
                DismissOnBack(enabled = bOpen) { closed += "b"; bOpen = false }
            }
        }.use { scene ->
            scene.settle()
            bOpen = true
            scene.settle()

            scene.pressEscape()
            assertEquals(listOf("b"), closed, "opened last, closed first")
            scene.pressEscape()
            assertEquals(listOf("b", "a"), closed)
        }
    }

    @Test
    fun `with nothing open the stack does not take back`() {
        val layers = WindowLayers()
        LayerScene(layers) { LayerHost(layers, Modifier.fillMaxSize()) {} }.use { scene ->
            scene.settle()
            assertFalse(layers.dismiss.handlesBack, "back goes to whatever else wants it")
            assertFalse(layers.dismiss.dismissTop())
        }
    }

    @Test
    fun `a focused field of the host gives up the keyboard before anything closes`() {
        val layers = WindowLayers()
        val focus = FocusRequester()
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Field(Modifier.focusRequester(focus))
                DismissOnBack { closed++ }
            }
        }.use { scene ->
            scene.settle()
            focus.requestFocus()
            scene.settle()
            assertTrue(layers.dismiss.fieldFocused, "a focused field opens a text input session the host sees")

            scene.pressEscape()
            assertFalse(layers.dismiss.fieldFocused, "the first Escape leaves the field")
            assertEquals(0, closed, "and closes nothing")

            scene.pressEscape()
            assertEquals(1, closed)
        }
    }

    @Test
    fun `a field that uses Escape itself keeps it`() {
        val layers = WindowLayers()
        val focus = FocusRequester()
        var closed = 0
        var fieldEscapes = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Field(
                    Modifier.focusRequester(focus).onPreviewKeyEvent {
                        if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { fieldEscapes++; true } else false
                    },
                )
                DismissOnBack { closed++ }
            }
        }.use { scene ->
            scene.settle()
            focus.requestFocus()
            scene.settle()

            scene.pressEscape()
            assertEquals(1, fieldEscapes)
            assertEquals(0, closed)
            assertTrue(layers.dismiss.fieldFocused, "the field used the key, so nothing took the keyboard from it")
        }
    }

    // The case a context menu is: a focusable popup over a page that has something
    // of its own open. Escape is the menu's.
    @Test
    fun `Escape closes a popup opened over an open entry and leaves the entry`() {
        val layers = WindowLayers()
        var menuOpen by mutableStateOf(true)
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed++ }
                if (menuOpen) {
                    Popup(onDismissRequest = { menuOpen = false }, properties = PopupProperties(focusable = true)) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            scene.pressEscape()
            assertFalse(menuOpen, "the popup registered for back after the stack, so it is asked first")
            assertEquals(0, closed)

            scene.pressEscape()
            assertEquals(1, closed, "with the popup gone the stack gets the next press")
        }
    }

    // A popup with a field focused in it: the field is the popup's, not the host's,
    // and Escape has to reach the popup rather than be spent on the host's focus.
    @Test
    fun `Escape closes a popup whose field holds the keyboard`() {
        val layers = WindowLayers()
        var popupOpen by mutableStateOf(true)
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed++ }
                if (popupOpen) {
                    Popup(onDismissRequest = { popupOpen = false }, properties = PopupProperties(focusable = true)) {
                        FocusedField()
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            assertTrue(layers.dismiss.fieldFocused, "the popup's field holds a session")
            scene.pressEscape()
            assertFalse(popupOpen)
            assertEquals(0, closed)
        }
    }

    // The two-factor code dialog focuses its field as it opens.
    @Test
    fun `Escape closes a dialog whose field holds the keyboard`() {
        val layers = WindowLayers()
        var dialogOpen by mutableStateOf(true)
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed++ }
                if (dialogOpen) {
                    Dialog(onDismissRequest = { dialogOpen = false }) { FocusedField() }
                }
            }
        }.use { scene ->
            scene.settle()
            scene.pressEscape()
            assertFalse(dialogOpen)
            assertEquals(0, closed)
        }
    }

    @Test
    fun `an entry of a layer closes before a newer one of the window's own content`() {
        val layers = WindowLayers()
        var baseOpen by mutableStateOf(false)
        val closed = mutableListOf<String>()
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = true) { DismissOnBack { closed += "layer" } }
                DismissOnBack(enabled = baseOpen) { closed += "base" }
            }
        }.use { scene ->
            scene.settle()
            baseOpen = true
            scene.settle()
            scene.pressEscape()
            assertEquals(listOf("layer"), closed, "what draws on top closes first")
        }
    }

    @Test
    fun `a closable layer is an entry of its own`() {
        val layers = WindowLayers()
        var open by mutableStateOf(true)
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = open, onDismissRequest = { open = false }) {}
            }
        }.use { scene ->
            scene.settle()
            scene.pressEscape()
            assertFalse(open)
            assertEquals(0, layers.residents.size)
        }
    }

    @Test
    fun `under a modal the entries below it are out of reach`() {
        val layers = WindowLayers()
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed++ }
                Layer(visible = true, modal = true) {}
            }
        }.use { scene ->
            scene.settle()
            scene.pressEscape()
            assertEquals(0, closed, "Escape does not reach behind a modal that cannot be closed")
            assertFalse(layers.dismiss.canDismiss)
            assertTrue(layers.dismiss.handlesBack, "and back does not navigate behind it either")
            assertFalse(layers.dismiss.dismissTop())
        }
    }

    @Test
    fun `back closes the top entry through the same stack`() {
        val layers = WindowLayers()
        val closed = mutableListOf<String>()
        val first = layers.dismiss.push(null) { closed += "first" }
        layers.dismiss.push(null) { closed += "second" }
        assertTrue(layers.dismiss.handlesBack)
        assertTrue(layers.dismiss.dismissTop())
        assertEquals(listOf("second"), closed)

        first.close()
        first.close()
        assertTrue(layers.dismiss.canDismiss, "closing a handle twice takes out only its own entry")
    }

    @Test
    fun `one press closes one thing however long it is held`() {
        val layers = WindowLayers()
        val closed = mutableListOf<String>()
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                var aOpen by remember { mutableStateOf(true) }
                var bOpen by remember { mutableStateOf(true) }
                DismissOnBack(enabled = aOpen) { closed += "a"; aOpen = false }
                DismissOnBack(enabled = bOpen) { closed += "b"; bOpen = false }
            }
        }.use { scene ->
            scene.settle()
            scene.escape(KeyEventType.KeyDown)
            scene.escape(KeyEventType.KeyDown)
            scene.escape(KeyEventType.KeyDown)
            scene.escape(KeyEventType.KeyUp)
            assertEquals(listOf("b"), closed)
        }
    }

    // A popup closes on the first press of a held Escape. The repeats come after it,
    // and the first of them must not look like a press of its own to the stack.
    @Test
    fun `the repeats of a press a popup took close nothing behind it`() {
        val layers = WindowLayers()
        var popupOpen by mutableStateOf(true)
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed++ }
                if (popupOpen) {
                    Popup(onDismissRequest = { popupOpen = false }, properties = PopupProperties(focusable = true)) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            scene.escape(KeyEventType.KeyDown)
            assertFalse(popupOpen)
            scene.escape(KeyEventType.KeyDown)
            scene.escape(KeyEventType.KeyDown)
            scene.escape(KeyEventType.KeyUp)
            assertEquals(0, closed)

            scene.pressEscape()
            assertEquals(1, closed, "the next press is a press")
        }
    }

    // A window that loses the focus mid-press never sees the release. The image scene
    // has no window focus to lose, so the reset the host runs on that edge is called
    // directly.
    @Test
    fun `a press cut off by losing the window does not swallow the next one`() {
        val layers = WindowLayers()
        val stack = layers.dismiss
        stack.onPreviewKeyEvent(escapeEvent(KeyEventType.KeyDown))
        assertFalse(stack.onKeyEvent(escapeEvent(KeyEventType.KeyDown)), "a first press goes on to back")
        stack.onPreviewKeyEvent(escapeEvent(KeyEventType.KeyDown))
        assertTrue(stack.onKeyEvent(escapeEvent(KeyEventType.KeyDown)), "a repeat is taken")

        stack.resetKeys()
        stack.onPreviewKeyEvent(escapeEvent(KeyEventType.KeyDown))
        assertFalse(stack.onKeyEvent(escapeEvent(KeyEventType.KeyDown)), "after the reset a press is a first press again")
    }

    @Test
    fun `on Android a repeat is taken so the system does not turn it into back`() {
        val layers = WindowLayers()
        var closed = 0
        layers.dismiss.push(null) { closed++ }
        val stack = layers.dismiss
        // The fallback key reaches back only for a key the window did not take, and
        // the window's key hook is the last thing to see it before that.
        stack.onPreviewKeyEvent(escapeEvent(KeyEventType.KeyDown))
        assertFalse(stack.onKeyEvent(escapeEvent(KeyEventType.KeyDown)))
        stack.dismissTop()
        stack.onPreviewKeyEvent(escapeEvent(KeyEventType.KeyDown))
        assertTrue(stack.onKeyEvent(escapeEvent(KeyEventType.KeyDown)), "the repeat never becomes a second back")
        assertEquals(1, closed)
    }
}

@Composable
private fun Field(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    BasicTextField(text, { text = it }, modifier)
}

@Composable
private fun FocusedField() {
    val focus = remember { FocusRequester() }
    Field(Modifier.focusRequester(focus))
    LaunchedEffect(Unit) { focus.requestFocus() }
}
