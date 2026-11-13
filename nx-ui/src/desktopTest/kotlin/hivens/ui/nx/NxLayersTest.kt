package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.layers.DismissOnBack
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The library's popups and dialogs as layers of one window: what draws above what,
 * what takes the pointer and the keyboard, and what Escape closes in which order.
 */
class NxLayersTest {

    @Test
    fun `a menu opened from inside a modal dialog draws above it and takes input`() = onEventThread {
        var picked = 0
        LayersWindow {
            NxDialog(onDismissRequest = {}) {
                Box(Modifier.size(300.dp).background(DIALOG_INK)) {
                    Box(Modifier.align(Alignment.Center).size(20.dp)) {
                        NxContextMenu(expanded = true, onDismissRequest = {}) {
                            Box(Modifier.size(60.dp).background(MENU_INK).clickable { picked++ })
                        }
                    }
                }
            }
        }.use { window ->
            window.settle(frames = 40)
            val menu = window.inkBounds(MENU_INK)
            assertTrue(menu.count > 1000, "the menu is drawn over the dialog it was opened from, saw ${menu.count} pixels")
            window.click(menu.centre)
            assertEquals(1, picked, "and the press lands on the menu, not on the dialog under it")
        }
    }

    @Test
    fun `Escape closes the topmost popup, then the dialog, then what was open behind them`() = onEventThread {
        val closed = mutableListOf<String>()
        var dialogOpen by mutableStateOf(true)
        var menuOpen by mutableStateOf(true)
        LayersWindow {
            DismissOnBack { closed += "entry" }
            if (dialogOpen) {
                NxDialog(onDismissRequest = { closed += "dialog"; dialogOpen = false }) {
                    Box(Modifier.size(200.dp)) {
                        NxContextMenu(expanded = menuOpen, onDismissRequest = { closed += "menu"; menuOpen = false }) {
                            NxMenuItem(label = "Item") {}
                        }
                    }
                }
            }
        }.use { window ->
            window.settle(frames = 30)
            window.pressEscape()
            window.settle(frames = 20)
            assertEquals(listOf("menu"), closed)
            window.pressEscape()
            assertEquals(listOf("menu", "dialog"), closed)
            window.pressEscape()
            assertEquals(listOf("menu", "dialog", "entry"), closed)
        }
    }

    @Test
    fun `the select still walks its list from the keyboard and Escape closes it`() = onEventThread {
        var chosen: String? = null
        LayersWindow {
            Box(Modifier.size(220.dp, 40.dp)) {
                NxSelect(
                    options  = listOf("Alpha", "Beta", "Gamma"),
                    selected = "Alpha",
                    onSelect = { chosen = it },
                    label    = { it },
                    modifier = Modifier.size(220.dp, 40.dp),
                )
            }
        }.use { window ->
            window.settle()
            window.click(Offset(100f, 20f))
            window.settle(frames = 20)
            assertTrue(window.layers.dismiss.canDismiss, "the list is open")
            window.press(Key.DirectionDown)
            window.press(Key.DirectionDown)
            window.press(Key.Enter)
            window.settle(frames = 20)
            assertEquals("Gamma", chosen, "two steps down from the answer in force")

            window.click(Offset(100f, 20f))
            window.settle(frames = 20)
            assertTrue(window.layers.dismiss.canDismiss)
            window.pressEscape()
            window.settle(frames = 20)
            assertFalse(window.layers.dismiss.canDismiss, "Escape reached the list through the window's stack")
            assertEquals("Gamma", chosen)
        }
    }

    // The create-pack dialog's version list: it hangs off the field that filters it,
    // and the field has to keep the keyboard while the list is up.
    @Test
    fun `a choice list that yields to its field leaves it the keyboard and closes on Escape first`() = onEventThread {
        val field = FocusRequester()
        var fieldFocused = false
        var open by mutableStateOf(false)
        LayersWindow {
            Box {
                Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
                NxChoiceMenu(expanded = open, onDismissRequest = { open = false }, yieldsToField = true) {
                    NxChoiceItem(label = "1.21.1", selected = false) {}
                }
            }
        }.use { window ->
            window.settle()
            field.requestFocus()
            window.settle()
            open = true
            window.settle(frames = 20)
            assertTrue(fieldFocused, "typing goes on into the field under the list")
            window.pressEscape()
            window.settle(frames = 20)
            assertFalse(open, "Escape closes the list first")
            assertTrue(fieldFocused, "and the field still has the keyboard")
        }
    }

    @Test
    fun `a context menu over a focused field takes the keyboard and gives it back`() = onEventThread {
        val field = FocusRequester()
        var fieldFocused = false
        var open by mutableStateOf(false)
        LayersWindow {
            Box {
                Field(Modifier.focusRequester(field).onFocusChanged { fieldFocused = it.isFocused })
                NxContextMenu(expanded = open, onDismissRequest = { open = false }) {
                    NxMenuItem(label = "Item") {}
                }
            }
        }.use { window ->
            window.settle()
            field.requestFocus()
            window.settle()
            open = true
            window.settle(frames = 20)
            assertFalse(fieldFocused, "typing does not reach a field behind a menu")
            window.pressEscape()
            window.settle(frames = 20)
            assertTrue(fieldFocused)
        }
    }

    @Test
    fun `the select gives the keyboard back to its trigger after a pick`() = onEventThread {
        var triggerHasFocus = false
        LayersWindow {
            Box(Modifier.size(220.dp, 40.dp).onFocusChanged { triggerHasFocus = it.hasFocus }) {
                NxSelect(
                    options  = listOf("Alpha", "Beta"),
                    selected = "Alpha",
                    onSelect = {},
                    label    = { it },
                    modifier = Modifier.size(220.dp, 40.dp),
                )
            }
        }.use { window ->
            window.settle()
            window.click(Offset(100f, 20f))
            window.settle(frames = 20)
            assertFalse(triggerHasFocus, "the open list holds the keyboard")
            window.press(Key.DirectionDown)
            window.press(Key.Enter)
            window.settle(frames = 20)
            assertTrue(triggerHasFocus, "and hands it back to the field it hangs off")
        }
    }

    @Test
    fun `a field asked for from inside a dialog's content gets the keyboard as the dialog opens`() = onEventThread {
        var fieldFocused = false
        LayersWindow {
            NxDialog(onDismissRequest = {}) {
                val focus = remember { FocusRequester() }
                Field(Modifier.focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused })
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }.use { window ->
            window.settle(frames = 10)
            assertTrue(fieldFocused)
            assertTrue(window.layers.dismiss.fieldFocused, "with a text session open, the caret and the soft keyboard follow")
        }
    }

    @Test
    fun `a dialog outside any layer host still covers the window and blocks what is under it`() = onEventThread {
        var behind = 0
        val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box(Modifier.size(80.dp).clickable { behind++ })
                    NxDialog(onDismissRequest = null) { Box(Modifier.size(100.dp).background(DIALOG_INK)) }
                }
            }
        }
        var time = 0L
        var frame: Bitmap? = null
        fun settle() = repeat(20) {
            Snapshot.sendApplyNotifications()
            time += 16_000_000L
            frame = Bitmap.makeFromImage(scene.render(time))
        }
        settle()
        scene.sendPointerEvent(PointerEventType.Press, Offset(40f, 40f), buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        scene.sendPointerEvent(PointerEventType.Release, Offset(40f, 40f), buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
        val bmp = checkNotNull(frame)
        var ink = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) if (near(bmp.getColor(x, y), DIALOG_INK.argb())) ink++
        scene.close()
        assertTrue(ink > 5000, "the dialog is drawn at its size, centred in the window, saw $ink pixels")
        assertEquals(0, behind, "and the window under it is out of reach")
    }

    @Test
    fun `a tooltip never takes Escape`() = onEventThread {
        var closed = 0
        LayersWindow {
            DismissOnBack { closed++ }
            NxTooltip(
                tooltip   = { Box(Modifier.size(40.dp).background(MENU_INK)) },
                behaviour = NxTooltipBehaviour.Label.copy(delayMillis = 0),
            ) {
                Box(Modifier.size(60.dp).background(DIALOG_INK))
            }
        }.use { window ->
            window.settle()
            window.hover(Offset(30f, 30f))
            window.settle(frames = 20)
            assertTrue(window.inkBounds(MENU_INK).count > 100, "the tooltip is up")
            window.pressEscape()
            assertEquals(1, closed, "Escape went past the tooltip to what was open behind it")
        }
    }

    @Test
    fun `a press outside closes a menu`() = onEventThread {
        var open by mutableStateOf(true)
        LayersWindow {
            Box(Modifier.size(20.dp)) {
                NxContextMenu(expanded = open, onDismissRequest = { open = false }) {
                    NxMenuItem(label = "Item") {}
                }
            }
        }.use { window ->
            window.settle(frames = 30)
            window.click(Offset(350f, 350f))
            assertFalse(open)
        }
    }

    @Test
    fun `a press outside a modal dialog with no dismiss request closes nothing and reaches nothing`() = onEventThread {
        var behind = 0
        LayersWindow {
            Box(Modifier.size(80.dp).clickable { behind++ })
            NxDialog(onDismissRequest = null) { Box(Modifier.size(100.dp).background(DIALOG_INK)) }
        }.use { window ->
            window.settle(frames = 30)
            window.click(Offset(40f, 40f))
            window.settle(frames = 10)
            assertEquals(0, behind, "the window under the dialog is out of reach")
            assertTrue(window.inkBounds(DIALOG_INK).count > 5000, "and the dialog is still up")
            window.pressEscape()
            assertTrue(window.layers.dismiss.handlesBack, "nor does back go behind it")
            assertTrue(window.inkBounds(DIALOG_INK).count > 5000)
        }
    }

    @Test
    fun `composition locals of the call site reach the content of every kind of layer`() = onEventThread {
        val callSite = compositionLocalOf { "the window" }
        var inMenu: String? = null
        var inDialog: String? = null
        var inTooltip: String? = null
        LayersWindow {
            CompositionLocalProvider(callSite provides "the screen") {
                Box(Modifier.size(20.dp)) {
                    NxContextMenu(expanded = true, onDismissRequest = {}) { inMenu = callSite.current }
                }
                NxDialog(onDismissRequest = {}) {
                    inDialog = callSite.current
                    // Inside the dialog, the only place the pointer reaches while it is up.
                    NxTooltip(
                        tooltip   = { inTooltip = callSite.current },
                        behaviour = NxTooltipBehaviour.Label.copy(delayMillis = 0),
                    ) { Box(Modifier.size(100.dp)) }
                }
            }
        }.use { window ->
            window.settle(frames = 20)
            window.hover(Offset(200f, 200f))
            window.settle(frames = 20)
            assertEquals("the screen", inMenu)
            assertEquals("the screen", inDialog)
            assertEquals("the screen", inTooltip)
        }
    }
}

@Composable
private fun Field(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    BasicTextField(text, { text = it }, modifier.size(120.dp, 30.dp))
}

private val DIALOG_INK = Color(0xFF3355FF)
private val MENU_INK = Color(0xFFFF2200)

/** Runs [body] on the event thread, the one the snapshot pump the scene's effects resume on runs on. */
private fun onEventThread(body: () -> Unit) {
    var outcome: Result<Unit>? = null
    SwingUtilities.invokeAndWait { outcome = runCatching(body) }
    outcome!!.getOrThrow()
}

/**
 * An off-screen window of 400 by 400 at density 1, with its layers at the root the
 * way the shell mounts them.
 *
 * A key goes the way the window sends it: the window's preview hook, whatever holds
 * the focus, the window's key hook, and an Escape nothing used becomes back. With no
 * Compose popup or dialog in the window, back goes to the stack alone, so it is asked
 * directly where the window's back input would ask it.
 */
@OptIn(ExperimentalComposeUiApi::class)
private class LayersWindow(content: @Composable () -> Unit) : AutoCloseable {
    val layers = WindowLayers()
    private val scene = ImageComposeScene(width = 400, height = 400, density = Density(1f)) {
        NxTheme(dark = true) {
            LayerHost(layers, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
    }
    private var time = 0L
    private var last: Bitmap? = null

    fun settle(frames: Int = 6) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            time += 16_000_000L
            last = Bitmap.makeFromImage(scene.render(time))
        }
    }

    fun hover(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Enter, at)
        scene.sendPointerEvent(PointerEventType.Move, at)
        settle()
    }

    fun click(at: Offset) {
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        settle()
        scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }

    fun press(key: Key) {
        send(key, KeyEventType.KeyDown)
        send(key, KeyEventType.KeyUp)
    }

    fun pressEscape() = press(Key.Escape)

    private fun send(key: Key, type: KeyEventType) {
        val event = keyEvent(key, type)
        layers.dismiss.onPreviewKeyEvent(event)
        val used = scene.sendKeyEvent(event) || layers.dismiss.onKeyEvent(event)
        if (!used && key == Key.Escape && type == KeyEventType.KeyDown && layers.dismiss.handlesBack) {
            layers.dismiss.dismissTop()
        }
        settle()
    }

    /** Where [ink] is in the last frame: how many pixels, and the middle of them. */
    fun inkBounds(ink: Color): Ink {
        val bmp = last ?: return Ink(0, Offset.Zero)
        val want = ink.argb()
        var count = 0
        var sx = 0L
        var sy = 0L
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (near(bmp.getColor(x, y), want)) {
                    count++
                    sx += x
                    sy += y
                }
            }
        }
        return Ink(count, if (count == 0) Offset.Zero else Offset(sx.toFloat() / count, sy.toFloat() / count))
    }

    override fun close() = scene.close()
}

private class Ink(val count: Int, val centre: Offset)

private fun Color.argb(): Int =
    (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

private fun near(a: Int, b: Int): Boolean {
    fun ch(v: Int, s: Int) = (v shr s) and 0xFF
    return abs(ch(a, 16) - ch(b, 16)) < 12 && abs(ch(a, 8) - ch(b, 8)) < 12 && abs(ch(a, 0) - ch(b, 0)) < 12
}

// A test has no window to deliver an AWT event through, and this factory is the one
// way to make a key event without one.
@OptIn(InternalComposeUiApi::class)
private fun keyEvent(key: Key, type: KeyEventType): KeyEvent = KeyEvent(key, type)
