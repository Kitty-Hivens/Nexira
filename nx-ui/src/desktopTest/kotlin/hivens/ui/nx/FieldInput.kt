package hivens.ui.nx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineDispatcher
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

/**
 * A text field in a scene, typed into the way an input method types into it: through the
 * session the field opens with the platform once it has the focus. Keys go in as key
 * events. Every call is meant for the AWT event thread, see [onEdt].
 *
 * Work the scene dispatches waits for the next [settle], the way a window's event loop
 * runs it after the events already queued. An input method's word followed by a key
 * that was queued behind it reaches the field in that order and with nothing run in
 * between, which is what [commit] then [press] reproduces.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal class FieldInput(width: Int = 300, height: Int = 60, content: @Composable (Modifier) -> Unit) : AutoCloseable {
    private val focus = FocusRequester()
    private var request: PlatformTextInputMethodRequest? = null
    private var time = 0L
    private val loop = EventLoop()

    private val scene = ImageComposeScene(width, height, density = Density(1f), coroutineContext = loop) {
        InterceptPlatformTextInput({ asked, next ->
            request = asked
            next.startInputMethod(asked)
        }) {
            content(Modifier.focusRequester(focus))
        }
    }

    /** Frames enough for the field to settle with its caller, past the frames it gives a caller to answer. */
    fun settle(frames: Int = 16) = repeat(frames) {
        loop.drain()
        Snapshot.sendApplyNotifications()
        loop.drain()
        time += 16_000_000L
        scene.render(time)
    }

    fun focus() {
        focus.requestFocus()
        settle()
    }

    /** What the field shows: the text its input session holds. */
    val shown: String get() = session().value().text

    /** Whether an input method has a word open in the field. */
    val composing: Boolean get() = session().value().composition != null

    /** Text committed at the caret, as a keyboard or an input method's last word lands. */
    fun commit(text: String) = edit(CommitTextCommand(text, 1))

    /** A word the input method holds open, shown underlined until it is committed. */
    fun compose(text: String) = edit(SetComposingTextCommand(text, 1))

    fun edit(vararg commands: EditCommand) = session().onEditCommand(commands.toList())

    /** A whole press of [key], down and up, with nothing drawn in between. */
    fun press(key: Key, ctrl: Boolean = false): Boolean {
        val used = scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
        scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
        return used
    }

    private fun session() = checkNotNull(request) { "the field has no input session: it never took the focus" }

    override fun close() {
        scene.close()
        loop.drain()
    }
}

/** Holds what is dispatched to it until [drain], as an event loop holds work behind the event it is on. */
private class EventLoop : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().run()
    }
}

/** Runs [block] on the AWT event thread, where the scene and its collectors live, and hands back its result. */
internal fun <T> onEdt(block: () -> T): T {
    var out: Result<T>? = null
    SwingUtilities.invokeAndWait { out = runCatching(block) }
    return out!!.getOrThrow()
}
