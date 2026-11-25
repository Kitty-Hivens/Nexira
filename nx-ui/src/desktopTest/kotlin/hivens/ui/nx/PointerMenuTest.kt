package hivens.ui.nx

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalLocalization
import androidx.compose.ui.platform.PlatformLocalization
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.onEventThread
import hivens.ui.theme.NxThemed
import hivens.ui.theme.TestTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A right click opens the library's menu: where the pointer is, over a text field without taking the
 * keyboard or the selection from it, and over anything else as a menu does.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PointerMenuTest {

    @Test
    fun `copying from a field's menu copies what was selected, and the field keeps the keyboard`() = onEventThread {
        var focused = false
        val field = FocusRequester()
        Scene {
            NxBasicTextField("selected words", {}, Modifier.offset(40.dp, 60.dp).width(200.dp).focusRequester(field).onFocusChanged { focused = it.isFocused }, singleLine = true)
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            scene.press(Key.A, ctrl = !onMac, meta = onMac)
            scene.rightClick(Offset(60f, 68f))
            assertTrue(focused, "the field holds the keyboard under the menu")
            scene.click(assertNotNull(scene.row(Words.copy), "the menu offers the copy").center)
            scene.settleUntil { scene.clipboard.text != null }
            assertEquals("selected words", scene.clipboard.text)
            assertNull(scene.row(Words.copy), "and is gone once something in it was chosen")
            assertTrue(focused, "the field still has the keyboard")
        }
    }

    @Test
    fun `Escape closes the menu and leaves the field its keyboard`() = onEventThread {
        var focused = false
        val field = FocusRequester()
        Scene {
            NxBasicTextField("selected words", {}, Modifier.width(200.dp).focusRequester(field).onFocusChanged { focused = it.isFocused }, singleLine = true)
        }.use { scene ->
            scene.settle()
            field.requestFocus()
            scene.settle()
            scene.press(Key.A, ctrl = !onMac, meta = onMac)
            scene.rightClick(Offset(20f, 8f))
            assertNotNull(scene.row(Words.copy))
            scene.press(Key.Escape)
            assertNull(scene.row(Words.copy))
            assertTrue(focused)
        }
    }

    @Test
    fun `the menu opens at the pointer, takes the keyboard from what is no field and gives it back`() = onEventThread {
        var focused = false
        var ran = 0
        val area = FocusRequester()
        Scene {
            Box(Modifier.offset(50.dp, 40.dp)) {
                ContextMenuArea({ listOf(ContextMenuItem("Act") { ran++ }) }) {
                    Box(Modifier.size(200.dp).focusRequester(area).onFocusChanged { focused = it.isFocused }.focusable())
                }
            }
        }.use { scene ->
            scene.settle()
            area.requestFocus()
            scene.settle()
            scene.rightClick(Offset(120f, 90f))
            assertTrue(!focused, "a menu has the keyboard while it is up")
            val row = assertNotNull(scene.row("Act"))
            assertTrue(row.left in 120f..150f && row.top in 90f..120f, "hung off the pointer and not off the window's corner, was $row")
            scene.click(row.center)
            assertEquals(1, ran)
            assertNull(scene.row("Act"))
            assertTrue(focused, "and the keyboard went back")
        }
    }

    @Test
    fun `a press outside closes it, and a menu with nothing in it stands in nobody's way`() = onEventThread {
        var ran = 0
        var items by mutableStateOf(emptyList<ContextMenuItem>())
        Scene {
            ContextMenuArea({ items }) { Box(Modifier.size(300.dp)) }
        }.use { scene ->
            scene.settle()
            scene.rightClick(Offset(40f, 40f))
            items = listOf(ContextMenuItem("Act") { ran++ })
            scene.settle()
            assertNull(scene.row("Act"), "the empty menu was closed and does not fill up later")
            scene.rightClick(Offset(40f, 40f))
            assertNotNull(scene.row("Act"), "and the next right click opens one again")
            scene.click(Offset(290f, 290f))
            assertNull(scene.row("Act"))
            assertEquals(0, ran)
        }
    }

    private object Words : PlatformLocalization {
        override val copy = "copy it"
        override val cut = "cut it"
        override val paste = "paste it"
        override val selectAll = "select it all"
    }

    private class Scene(content: @Composable () -> Unit) : AutoCloseable {
        val clipboard = TestClipboard()
        private val layers = WindowLayers()
        private var time = 0L
        private val scene = ImageComposeScene(400, 400, Density(1f)) {
            CompositionLocalProvider(LocalClipboard provides clipboard, LocalLocalization provides Words) {
                NxThemed(TestTheme, dark = true, reduceMotion = true) {
                    LayerHost(layers, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) { content() } }
                }
            }
        }

        fun settle(frames: Int = 40) = repeat(frames) {
            Snapshot.sendApplyNotifications()
            time += 16_000_000L
            scene.render(time).close()
        }

        fun settleUntil(done: () -> Boolean) {
            repeat(100) {
                if (done()) return settle()
                Thread.sleep(10)
                settle(1)
            }
            throw AssertionError("it never happened")
        }

        fun click(at: Offset) = press(at, PointerButton.Primary, PointerButtons(isPrimaryPressed = true))

        fun rightClick(at: Offset) = press(at, PointerButton.Secondary, PointerButtons(isSecondaryPressed = true))

        private fun press(at: Offset, button: PointerButton, held: PointerButtons) {
            scene.sendPointerEvent(PointerEventType.Move, at)
            settle(2)
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = held, button = button)
            settle(2)
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), button = button)
            settle()
        }

        fun press(key: Key, ctrl: Boolean = false, meta: Boolean = false) {
            for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
                val event = KeyEvent(key, type, isCtrlPressed = ctrl, isMetaPressed = meta)
                layers.dismiss.onPreviewKeyEvent(event)
                val used = scene.sendKeyEvent(event) || layers.dismiss.onKeyEvent(event)
                if (!used && key == Key.Escape && type == KeyEventType.KeyDown && layers.dismiss.handlesBack) layers.dismiss.dismissTop()
            }
            settle()
        }

        /** Where the row saying [label] is, null where none is shown. */
        fun row(label: String): Rect? = scene.semanticsOwners
            .flatMap { it.rootSemanticsNode.all() }
            .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }
            ?.boundsInRoot

        private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

        override fun close() = scene.close()
    }
}
