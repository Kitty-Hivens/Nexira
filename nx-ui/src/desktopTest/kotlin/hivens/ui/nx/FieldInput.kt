package hivens.ui.nx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.TextRange
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
 *
 * The field copies to and pastes from [clipboard], never the system's.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal class FieldInput(width: Int = 300, height: Int = 60, content: @Composable (Modifier) -> Unit) : AutoCloseable {
    private val focus = FocusRequester()
    private var request: PlatformTextInputMethodRequest? = null
    private var time = 0L
    private val loop = EventLoop()

    val clipboard = TestClipboard()

    private val scene = ImageComposeScene(width, height, density = Density(1f), coroutineContext = loop) {
        InterceptPlatformTextInput({ asked, next ->
            request = asked
            next.startInputMethod(asked)
        }) {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                content(Modifier.focusRequester(focus))
            }
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

    /** Frames until [done], with work sent to other threads, a paste's read of the clipboard, given real time to come back. */
    fun settleUntil(what: String, done: () -> Boolean) {
        repeat(200) {
            if (done()) return
            Thread.sleep(10)
            settle(1)
        }
        throw AssertionError("$what never happened")
    }

    fun focus() {
        focus.requestFocus()
        settle()
    }

    /** What the field shows: the text its input session holds. */
    val shown: String get() = session().value().text

    /** The selection in the field, collapsed to the caret when nothing is selected. */
    val selection: TextRange get() = session().value().selection

    /** Whether an input method has a word open in the field. */
    val composing: Boolean get() = session().value().composition != null

    /** Text committed at the caret, as a keyboard or an input method's last word lands. */
    fun commit(text: String) = edit(CommitTextCommand(text, 1))

    /** A word the input method holds open, shown underlined until it is committed. */
    fun compose(text: String) = edit(SetComposingTextCommand(text, 1))

    fun edit(vararg commands: EditCommand) = session().onEditCommand(commands.toList())

    /**
     * A whole press of [key], down and up, with nothing drawn in between. [shortcut] holds the key
     * the system's shortcuts are made with, [word] the one that moves or erases a word at a time,
     * as [onMac] picks them, and [shift] holds Shift.
     */
    fun press(key: Key, shortcut: Boolean = false, word: Boolean = false, shift: Boolean = false): Boolean {
        fun event(type: KeyEventType) = KeyEvent(
            key,
            type,
            isCtrlPressed = (shortcut || word) && !onMac,
            isMetaPressed = shortcut && onMac,
            isAltPressed = word && onMac,
            isShiftPressed = shift,
        )
        val used = scene.sendKeyEvent(event(KeyEventType.KeyDown))
        scene.sendKeyEvent(event(KeyEventType.KeyUp))
        return used
    }

    /** The caret to the start of its line, selecting on the way with [select]: Home, and the command key with Left on macOS. */
    fun lineStart(select: Boolean = false) =
        if (onMac) press(Key.DirectionLeft, shortcut = true, shift = select) else press(Key.MoveHome, shift = select)

    /** The caret to the end of its line, selecting on the way with [select]: End, and the command key with Right on macOS. */
    fun lineEnd(select: Boolean = false) =
        if (onMac) press(Key.DirectionRight, shortcut = true, shift = select) else press(Key.MoveEnd, shift = select)

    private fun session() = checkNotNull(request) { "the field has no input session: it never took the focus" }

    override fun close() {
        scene.close()
        loop.drain()
    }
}

/**
 * Whether the framework's text fields read their shortcuts the macOS way here, which its own key
 * mapping decides by an os.name of exactly "Mac OS X". There the shortcuts are made with the
 * command key, a word at a time with the option key, and the ends of a line in a field of several
 * lines with the command key and Left or Right, while Home and End go to the ends of the whole
 * text. Everywhere else, Windows and Linux and a system it does not know, Ctrl makes both the
 * shortcuts and the word moves, and Home and End go to the ends of the line.
 */
internal val onMac: Boolean = System.getProperty("os.name") == "Mac OS X"

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
