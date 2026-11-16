package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * A scene typed into the way a person types: an input method's words through the input
 * session of the field that has the focus, keys as key events. Every call is meant for
 * the AWT event thread, see [onEventThread].
 *
 * Work the scene dispatches waits for the next [settle], the way a window's event loop
 * runs it after the events already queued. An input method's word and a key queued
 * behind it reach the field in that order with nothing run in between, which is what
 * [commit] followed by [press] reproduces.
 *
 * Fields copy to and paste from [clipboard], never the system's.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
internal class TypingScene(width: Int, height: Int, content: @Composable () -> Unit) : AutoCloseable {
    private var request: PlatformTextInputMethodRequest? = null
    private var time = 0L
    private val loop = EventLoop()

    val clipboard = TestClipboard()

    val scene = ImageComposeScene(width, height, density = Density(1f), coroutineContext = loop) {
        InterceptPlatformTextInput({ asked, next ->
            request = asked
            next.startInputMethod(asked)
        }) {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                content()
            }
        }
    }

    /** Frames enough for a field to settle with its caller, past the frames it gives a caller to answer. */
    fun settle(frames: Int = 16) = repeat(frames) {
        loop.drain()
        Snapshot.sendApplyNotifications()
        loop.drain()
        time += FRAME_NANOS
        scene.render(time).close()
    }

    /** Frames until [done], with work sent to other threads given real time to come back. */
    fun settleUntil(what: String, done: () -> Boolean) {
        repeat(200) {
            if (done()) return
            Thread.sleep(10)
            settle(1)
        }
        throw AssertionError("$what never happened")
    }

    /** The text fields on screen, in the order they are laid out. */
    fun fields(): List<SemanticsNode> =
        scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.withDescendants() }
            .filter { SemanticsProperties.EditableText in it.config }

    /** Gives the [index]th field the focus, as a press on it would. */
    fun focusField(index: Int = 0) {
        val node = fields()[index]
        val request = checkNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action) { "the field takes no focus" }
        check(request()) { "the field refused the focus" }
        settle()
    }

    /** What the [index]th field holds, as its semantics report it. */
    fun fieldText(index: Int = 0): String = fields()[index].config[SemanticsProperties.EditableText].text

    /** What the [index]th field draws: its text as laid out, after any masking. */
    fun drawnText(index: Int = 0): String {
        val layouts = mutableListOf<TextLayoutResult>()
        val get = checkNotNull(fields()[index].config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
        check(get(layouts)) { "the field has no text laid out" }
        return layouts.single().layoutInput.text.text
    }

    /** What the focused field shows: the text its input session holds. */
    val shown: String get() = session().value().text

    /** The selection in the focused field, collapsed to the caret when nothing is selected. */
    val selection: TextRange get() = session().value().selection

    /** What is selected in the focused field. */
    val selected: String get() = shown.substring(selection.min, selection.max)

    /** Whether an input method has a word open in the focused field. */
    val composing: Boolean get() = session().value().composition != null

    /** Text committed at the caret, as a keyboard or an input method's finished word lands. */
    fun commit(text: String) = edit(CommitTextCommand(text, 1))

    /** A word the input method holds open, shown underlined until it is committed. */
    fun compose(text: String) = edit(SetComposingTextCommand(text, 1))

    fun edit(vararg commands: EditCommand) = session().onEditCommand(commands.toList())

    /**
     * A whole press of [key], down and up, with nothing run in between. [shortcut] holds the key
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

    /** The paste shortcut, then frames until the focused field's text has changed: a paste reads the clipboard on another thread. */
    fun paste() {
        val before = shown
        press(Key.V, shortcut = true)
        settleUntil("the paste") { shown != before }
    }

    /** The shortcut with [key], then frames until the clipboard no longer holds what it held. */
    fun pressToClipboard(key: Key) {
        val before = clipboard.text
        press(key, shortcut = true)
        settleUntil("the clipboard write") { clipboard.text != before }
    }

    /** Frames with real time between them, enough for a clipboard read or write on another thread to have come back. */
    fun settleAWhile() = repeat(30) {
        Thread.sleep(10)
        settle(1)
    }

    private fun session() = checkNotNull(request) { "no field has an input session: none took the focus" }

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

private fun SemanticsNode.withDescendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.withDescendants() }

/** Holds what is dispatched to it until [drain], as an event loop holds work behind the event it is on. */
private class EventLoop : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queue) { queue.addLast(block) }
    }

    fun drain() {
        while (true) {
            val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return
            next.run()
        }
    }
}
