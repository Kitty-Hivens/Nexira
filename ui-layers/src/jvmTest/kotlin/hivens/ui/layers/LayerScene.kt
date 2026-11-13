package hivens.ui.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * An off-screen window for the layer tests: 300 by 300 at density 1, so a dp is a
 * pixel.
 *
 * A key goes the way a Compose window sends it: the window's preview hook, the
 * scene (the focused layer, a popup or a dialog included), the window's key hook,
 * and last the window's back input, which turns an Escape press nobody used into
 * back on the scene's navigation dispatcher. The hooks are wired to [layers] as the
 * shell wires them. An image scene has no window and no back input of its own, and
 * the window's input class is internal to Compose, so a direct input on the same
 * dispatcher does what it does: back completed on an Escape press, nothing else.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal class LayerScene(
    private val layers: WindowLayers? = null,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val backInput = DirectNavigationEventInput()
    private val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f)) {
        val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        DisposableEffect(dispatcher) {
            dispatcher?.addInput(backInput)
            onDispose { dispatcher?.removeInput(backInput) }
        }
        content()
    }
    private var time = 0L

    fun settle() {
        repeat(4) {
            Snapshot.sendApplyNotifications()
            time += 16_000_000L
            scene.render(time)
        }
    }

    fun click(x: Float, y: Float) {
        scene.sendPointerEvent(
            PointerEventType.Press, Offset(x, y),
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
        )
        settle()
        scene.sendPointerEvent(PointerEventType.Release, Offset(x, y), buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }

    /** One edge of Escape through the window. */
    fun escape(type: KeyEventType) {
        val event = escapeEvent(type)
        layers?.dismiss?.onPreviewKeyEvent(event)
        val used = scene.sendKeyEvent(event) || layers?.dismiss?.onKeyEvent(event) == true
        if (!used && type == KeyEventType.KeyDown) backInput.backCompleted()
        settle()
    }

    /** A whole press of Escape. */
    fun pressEscape() {
        escape(KeyEventType.KeyDown)
        escape(KeyEventType.KeyUp)
    }

    override fun close() = scene.close()
}

// A test has no window to deliver an AWT event through, and this factory is the one
// way to make a key event without one.
@OptIn(InternalComposeUiApi::class)
internal fun escapeEvent(type: KeyEventType): KeyEvent = KeyEvent(Key.Escape, type)
