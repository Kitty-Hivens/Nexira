package hivens.ui.layers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a layer does with the keyboard and where an anchored one lands: the parts a
 * Compose popup or dialog did by itself and a layer now has to.
 */
class LayerFocusTest {

    @Test
    fun `a focusable layer takes the focus and gives it back to the field that had it`() {
        val layers = WindowLayers()
        val field = FocusRequester()
        var open by mutableStateOf(false)
        var fieldFocused = false
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
                Layer(visible = open, modal = true, onDismissRequest = { open = false }) {}
            }
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            assertTrue(fieldFocused)

            open = true
            scene.settle()
            assertFalse(fieldFocused, "the layer took the keyboard")
            assertTrue(layers.residents.single().tookFocus, "and let it go, with nothing in the layer asking for it")

            open = false
            scene.settle()
            assertTrue(fieldFocused, "and handed it back where it was")
        }
    }

    @Test
    fun `a layer that is not focusable leaves the focus where it is`() {
        val layers = WindowLayers()
        val field = FocusRequester()
        var fieldFocused = false
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
                Layer(visible = true) { Box(Modifier.size(20.dp)) }
            }
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            assertTrue(fieldFocused, "a tooltip never takes the keyboard")
        }
    }

    // A layer is drawn inside the host, so its keys pass the host's own key hook,
    // where a field of the window's content gives up the keyboard first. A field in
    // the layer does not: Escape closes the layer as it closed a Compose dialog.
    @Test
    fun `Escape closes a layer whose own field holds the keyboard in one press`() {
        val layers = WindowLayers()
        var open by mutableStateOf(true)
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Layer(visible = open, modal = true, onDismissRequest = { open = false }) { FocusedField() }
            }
        }.use { scene ->
            scene.settle()
            assertTrue(layers.dismiss.fieldFocused)
            scene.pressEscape()
            assertFalse(open)
        }
    }

    // The three answers to Escape in a field, side by side with the one above for a
    // field inside a layer: a window field lets go first, a persistent one never does.
    @Test
    fun `Escape in a window field lets go of the keyboard first, then reaches the stack`() {
        val layers = WindowLayers()
        val field = FocusRequester()
        var fieldFocused = false
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed++ }
                Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
            }
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            scene.pressEscape()
            assertFalse(fieldFocused, "the first Escape takes the keyboard out of the field")
            assertEquals(0, closed)
            scene.pressEscape()
            assertEquals(1, closed, "the next one reaches the stack")
        }
    }

    @Test
    fun `Escape in a persistent field goes straight to the stack and leaves the field the keyboard`() {
        val layers = WindowLayers()
        val field = FocusRequester()
        var fieldFocused = false
        var closed = 0
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                DismissOnBack { closed++ }
                Field(Modifier.persistentInput().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
            }
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            scene.pressEscape()
            assertEquals(1, closed, "the press went on to what the window has open")
            assertTrue(fieldFocused, "and the field kept the keyboard")
        }
    }

    @Test
    fun `an anchored layer is placed by its provider against the layout it is called in`() {
        val layers = WindowLayers()
        var placed = Offset.Unspecified
        val belowStart = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(anchorBounds.left, anchorBounds.bottom)
        }
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Box(Modifier.offset(50.dp, 60.dp).size(30.dp, 20.dp)) {
                    AnchoredLayer(visible = true, position = belowStart) {
                        Box(Modifier.size(10.dp).onGloballyPositioned { placed = it.positionInWindow() })
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            assertEquals(Offset(50f, 80f), placed)
        }
    }

    // A version list hung off the field it filters: typing goes on into the field,
    // and the list closes on Escape before the field lets go of the keyboard.
    @Test
    fun `an anchored menu over a focused field leaves it the keyboard and closes on Escape first`() {
        val layers = WindowLayers()
        val field = FocusRequester()
        var open by mutableStateOf(false)
        var fieldFocused = false
        val below = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(anchorBounds.left, anchorBounds.bottom)
        }
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Box {
                    Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
                    AnchoredLayer(
                        visible = open,
                        position = below,
                        modal = true,
                        onDismissRequest = { open = false },
                        yieldsToField = true,
                    ) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            open = true
            scene.settle()
            assertTrue(fieldFocused, "the field keeps the keyboard under the list")

            scene.pressEscape()
            assertFalse(open, "the list closes first")
            assertTrue(fieldFocused)

            scene.pressEscape()
            assertFalse(fieldFocused, "then the field lets go")
        }
    }

    @Test
    fun `an anchored menu that does not yield takes the keyboard from the field and hands it back`() {
        val layers = WindowLayers()
        val field = FocusRequester()
        var open by mutableStateOf(false)
        var fieldFocused = false
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Box {
                    Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
                    AnchoredLayer(visible = open, position = Below, modal = true, onDismissRequest = { open = false }) {
                        Box(Modifier.size(40.dp))
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            open = true
            scene.settle()
            assertFalse(fieldFocused, "typing does not go on into the field behind a menu")
            scene.pressEscape()
            assertFalse(open)
            assertTrue(fieldFocused)
        }
    }

    @Test
    fun `Tab stays inside a modal layer, never on its root and never on what it blocks`() {
        val layers = WindowLayers()
        val first = FocusRequester()
        val last = FocusRequester()
        var focusManager: FocusManager? = null
        var at = ""
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                focusManager = LocalFocusManager.current
                Target("behind") { at = it }
                Layer(visible = true, modal = true, onDismissRequest = {}) {
                    Column {
                        Target("first", Modifier.focusRequester(first)) { at = it }
                        Target("last", Modifier.focusRequester(last)) { at = it }
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            val keys = checkNotNull(focusManager)
            assertEquals("", at, "a dialog opens with nothing focused")
            keys.moveFocus(FocusDirection.Next)
            scene.settle()
            assertTrue(at in setOf("first", "last"), "a Tab from nothing lands in the layer, not on what it blocks, saw '$at'")

            focus(last, scene)
            keys.moveFocus(FocusDirection.Next)
            scene.settle()
            assertEquals("last", at, "past the last control it stops")
            assertTrue(layers.residents.single().holdsFocus)

            focus(first, scene)
            keys.moveFocus(FocusDirection.Previous)
            scene.settle()
            assertEquals("first", at, "before the first it stops, and the invisible root never takes it")
            assertTrue(layers.residents.single().holdsFocus)
        }
    }

    // A trigger the layer came from sits deep in groups of its own, which the
    // window's content remembers only as its outermost child.
    @Test
    fun `focus goes back to a trigger nested in groups when it passes its own requester`() {
        val layers = WindowLayers()
        val trigger = FocusRequester()
        var open by mutableStateOf(false)
        var triggerFocused = false
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Box(Modifier.focusGroup()) {
                    Box(Modifier.focusGroup()) {
                        Target("trigger", Modifier.onFocusChanged { triggerFocused = it.isFocused }.focusRequester(trigger)) {}
                    }
                }
                Layer(visible = open, modal = true, onDismissRequest = { open = false }, returnFocus = trigger) {
                    Target("inside") {}
                }
            }
        }.use { scene ->
            scene.settle()
            trigger.requestFocus()
            scene.settle()
            open = true
            scene.settle()
            assertFalse(triggerFocused)
            open = false
            scene.settle()
            assertTrue(triggerFocused)
        }
    }

    @Test
    fun `without a requester the focus goes back into the group it was taken from`() {
        val layers = WindowLayers()
        val trigger = FocusRequester()
        var open by mutableStateOf(false)
        var groupHasFocus = false
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Box(Modifier.onFocusChanged { groupHasFocus = it.hasFocus }.focusGroup()) {
                    Box(Modifier.focusGroup()) {
                        Target("trigger", Modifier.focusRequester(trigger)) {}
                    }
                }
                Layer(visible = open, modal = true, onDismissRequest = { open = false }) { Target("inside") {} }
            }
        }.use { scene ->
            scene.settle()
            trigger.requestFocus()
            scene.settle()
            open = true
            scene.settle()
            assertFalse(groupHasFocus)
            open = false
            scene.settle()
            assertTrue(groupHasFocus, "the keyboard is back in the place the layer was opened from, not nowhere")
        }
    }

    @Test
    fun `a modal layer outside a host still covers the window and blocks what is under it`() {
        var behind = 0
        var inside = 0
        LayerScene {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.size(50.dp).clickable { behind++ })
                Layer(visible = true, modal = true, onDismissRequest = {}) {
                    Box(Modifier.align(Alignment.BottomEnd).size(50.dp).clickable { inside++ })
                }
            }
        }.use { scene ->
            scene.settle()
            scene.click(25f, 25f)
            assertEquals(0, behind)
            scene.click(275f, 275f)
            assertEquals(1, inside, "the content has the whole window to place itself in")
        }
    }

    @Test
    fun `a press on a modal anchored layer's own body does not close it`() {
        val layers = WindowLayers()
        var dismissed = 0
        val atAnchor = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ) = IntOffset(anchorBounds.left, anchorBounds.top)
        }
        LayerScene(layers) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Box(Modifier.offset(100.dp, 100.dp).size(10.dp)) {
                    AnchoredLayer(visible = true, position = atAnchor, modal = true, onDismissRequest = { dismissed++ }) {
                        Box(Modifier.size(80.dp))
                    }
                }
            }
        }.use { scene ->
            scene.settle()
            scene.click(140f, 140f)
            assertEquals(0, dismissed, "nothing in the body took the press, and it is still the body's")
            scene.click(20f, 20f)
            assertEquals(1, dismissed)
        }
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

private fun focus(requester: FocusRequester, scene: LayerScene) {
    requester.requestFocus()
    scene.settle()
}

private val Below = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ) = IntOffset(anchorBounds.left, anchorBounds.bottom)
}

/** A 40 by 40 focusable box that reports [name] when it takes the focus. */
@Composable
private fun Target(name: String, modifier: Modifier = Modifier, onFocused: (String) -> Unit) {
    Box(modifier.size(40.dp).onFocusChanged { if (it.isFocused) onFocused(name) }.focusable())
}
