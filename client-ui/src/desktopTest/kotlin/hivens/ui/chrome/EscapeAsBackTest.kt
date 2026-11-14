package hivens.ui.chrome

import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.layers.DismissOnBack
import hivens.ui.layers.Layer
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.layers.persistentInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Escape in a window whose platform makes no back of an unused Escape, as Android's
 * Compose does not: the shell's hooks are all there is between the key and nothing.
 * The rules are the desktop's. Inside a layer it closes the layer at once, a field of
 * the window lets go of the keyboard first, a persistent field never does, and with
 * nothing open it is back.
 */
@OptIn(ExperimentalComposeUiApi::class)
class EscapeAsBackTest {

    @Test
    fun `a layer closes on the first Escape`() {
        var open by mutableStateOf(true)
        PhoneWindow { Layer(visible = open, modal = true, onDismissRequest = { open = false }) { Box(Modifier.size(40.dp)) } }.use { w ->
            w.settle()
            assertTrue(w.pressEscape(), "the press is taken whole, so the root never sees it")
            assertFalse(open)
            assertEquals(0, w.backs, "the layer took the back, the history did not move")
        }
    }

    @Test
    fun `a layer whose own field holds the keyboard closes on the first Escape`() {
        var open by mutableStateOf(true)
        PhoneWindow { Layer(visible = open, modal = true, onDismissRequest = { open = false }) { FocusedField() } }.use { w ->
            w.settle()
            assertTrue(w.layers.dismiss.fieldFocused)
            w.pressEscape()
            assertFalse(open)
        }
    }

    @Test
    fun `a field of the window lets go first and the next Escape reaches the stack`() {
        val field = FocusRequester()
        var fieldFocused = false
        var closed = 0
        PhoneWindow {
            DismissOnBack { closed++ }
            Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
        }.use { w ->
            w.settle()
            field.requestFocus()
            w.settle()
            assertTrue(fieldFocused)
            w.pressEscape()
            assertFalse(fieldFocused, "the first Escape takes the keyboard out of the field")
            assertEquals(0, closed)
            w.pressEscape()
            assertEquals(1, closed, "the next one closes what the window has open")
        }
    }

    @Test
    fun `a persistent field keeps the keyboard and the press goes to the stack`() {
        val field = FocusRequester()
        var fieldFocused = false
        var closed = 0
        PhoneWindow {
            DismissOnBack { closed++ }
            Field(Modifier.persistentInput().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
        }.use { w ->
            w.settle()
            field.requestFocus()
            w.settle()
            w.pressEscape()
            assertEquals(1, closed)
            assertTrue(fieldFocused)
        }
    }

    @Test
    fun `with nothing open Escape is back, once per press however long it is held`() {
        PhoneWindow { Box(Modifier.size(40.dp).focusable()) }.use { w ->
            w.settle()
            assertTrue(w.pressEscape())
            assertEquals(1, w.backs)

            w.escape(KeyEventType.KeyDown)
            w.escape(KeyEventType.KeyDown)
            w.escape(KeyEventType.KeyDown)
            w.escape(KeyEventType.KeyUp)
            assertEquals(2, w.backs, "the repeats of a held key are not more backs")
        }
    }

    // A layer lets the focus go as it opens and its field asks for it right after. The
    // shell taking the focus back without looking took the caret out of the field.
    @Test
    fun `a field that asks for the keyboard as its layer opens keeps it`() {
        var open by mutableStateOf(false)
        var fieldFocused = false
        PhoneWindow {
            Layer(visible = open, modal = true, onDismissRequest = { open = false }) {
                FocusedField(Modifier.onFocusChanged { fieldFocused = it.isFocused })
            }
        }.use { w ->
            w.settle()
            open = true
            w.settle()
            w.settle()
            assertTrue(fieldFocused, "the shell took the keyboard back from the dialog's field")
        }
    }
}

/**
 * A window with the Android shell's key hooks over its layers and nothing else: no
 * back input of the window's own. Back goes where the activity's goes, the dismiss
 * stack's handler first and the history after it, which [backs] counts.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
private class PhoneWindow(content: @Composable () -> Unit) : AutoCloseable {
    val layers = WindowLayers()
    var backs = 0
        private set
    private val escape = EscapeAsBack(layers.dismiss) {
        if (layers.dismiss.handlesBack) layers.dismiss.dismissTop() else backs++
    }
    private val scene = ImageComposeScene(300, 300, density = Density(1f)) {
        Box(
            Modifier
                .fillMaxSize()
                .keepsFocus()
                .onPreviewKeyEvent { layers.dismiss.onPreviewKeyEvent(it) }
                .onKeyEvent(escape::onKeyEvent),
        ) {
            LayerHost(layers, Modifier.fillMaxSize()) { content() }
        }
    }
    private var time = 0L

    fun settle() {
        repeat(4) {
            Snapshot.sendApplyNotifications()
            time += 16_000_000L
            scene.render(time)
        }
    }

    /** One edge of Escape, and whether anything in the window took it. */
    fun escape(type: KeyEventType): Boolean = scene.sendKeyEvent(KeyEvent(Key.Escape, type)).also { settle() }

    /** A whole press of Escape, and whether its down edge was taken. */
    fun pressEscape(): Boolean = escape(KeyEventType.KeyDown).also { escape(KeyEventType.KeyUp) }

    override fun close() = scene.close()
}

@Composable
private fun Field(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    BasicTextField(text, { text = it }, modifier)
}

@Composable
private fun FocusedField(modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    Field(modifier.focusRequester(focus))
    LaunchedEffect(Unit) { focus.requestFocus() }
}
