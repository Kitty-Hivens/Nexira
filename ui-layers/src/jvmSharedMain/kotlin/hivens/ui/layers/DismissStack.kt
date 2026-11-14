package hivens.ui.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.util.concurrent.atomic.AtomicInteger

/**
 * What Escape and back close in one window, from the top.
 *
 * An entry is pushed for each thing that is open and can be closed: a sheet, a
 * confirmation, a step of the editor. Escape first takes the keyboard out of a
 * focused text field of the host's, and otherwise becomes back. Back goes to the
 * most recently registered thing that listens for it, so a Compose popup or
 * dialog opened over the window closes first, and the stack, registered at the
 * window's root, closes its top entry only when nothing above it took the press.
 * With nothing to close, back goes on to whatever else wants it.
 *
 * The top is decided with the layers: an entry belongs to the layer it was pushed
 * from (or to the window's own content), entries of a higher layer close first, and
 * within one layer the most recent closes first. While a modal is up, entries under
 * it are out of reach, as their layers are to the pointer.
 */
@Stable
class DismissStack internal constructor(private val layers: WindowLayers) {

    private class Entry(val owner: LayerResident?, val stamp: Long, val onDismiss: () -> Unit)

    private val entries = mutableStateListOf<Entry>()

    /**
     * Puts [onDismiss] on the stack for as long as the returned handle is open.
     * [owner] is the layer it belongs to, [LocalResident] where it is pushed from,
     * null for the window's own content. Closing the handle twice is harmless.
     */
    fun push(owner: LayerResident?, onDismiss: () -> Unit): AutoCloseable {
        val entry = Entry(owner, layers.tick(), onDismiss)
        entries.add(entry)
        return AutoCloseable { entries.remove(entry) }
    }

    private fun top(): Entry? {
        val plan = layers.plan
        var best: Entry? = null
        var bestRank = Int.MIN_VALUE
        for (entry in entries) {
            val rank = plan.rankOf(entry.owner)
            if (!plan.takesInput(rank)) continue
            val leader = best
            if (leader == null || rank > bestRank || (rank == bestRank && entry.stamp > leader.stamp)) {
                best = entry
                bestRank = rank
            }
        }
        return best
    }

    /** Whether Escape or back would close something now. */
    val canDismiss: Boolean get() = top() != null

    /**
     * Whether back belongs to this window's stack: something to close, or a modal
     * that holds the window. Under a modal with nothing to close, back does nothing
     * rather than navigate behind it.
     */
    val handlesBack: Boolean get() = canDismiss || layers.plan.modal != null

    /** Closes the top entry. False when there is nothing to close. */
    fun dismissTop(): Boolean {
        val entry = top() ?: return false
        entry.onDismiss()
        return true
    }

    // Text input sessions open under the host: a focused editable field. Counted
    // rather than flagged, because the next field opens its session before the last
    // one has closed.
    private val fieldSessions = AtomicInteger()

    internal fun fieldOpened() {
        fieldSessions.incrementAndGet()
    }

    internal fun fieldClosed() {
        fieldSessions.decrementAndGet()
    }

    // The part of those sessions opened inside a layer, counted by the layer's frame.
    private val layerFieldSessions = AtomicInteger()

    internal fun layerFieldOpened() {
        layerFieldSessions.incrementAndGet()
    }

    internal fun layerFieldClosed() {
        layerFieldSessions.decrementAndGet()
    }

    // Fields marked persistentInput that hold the focus now, see [persistentInput].
    private val persistentFields = AtomicInteger()

    internal fun persistentFocused() {
        persistentFields.incrementAndGet()
    }

    internal fun persistentReleased() {
        persistentFields.decrementAndGet()
    }

    /** Whether the focused field is one of the window's own content rather than of a layer. */
    private val contentFieldFocused: Boolean get() = fieldSessions.get() - layerFieldSessions.get() > 0

    /** Set by the host: takes the keyboard focus away from the focused field. */
    internal var leaveField: () -> Unit = {}

    /**
     * Whether a text field under the host holds a text input session. A field in a
     * layer, or in a Compose popup or dialog opened from under the host, counts too.
     */
    val fieldFocused: Boolean get() = fieldSessions.get() > 0

    // Escape as the window's first look at the key saw it: whether it is held, and
    // whether the press going through now is the first of its press or a repeat. A
    // key held down repeats its press, and every repeat would otherwise reach back.
    private var held = false
    private var fresh = true
    // A press the host used to take the keyboard out of a field, whose repeats and
    // release are the host's too.
    private var pressTaken = false

    /**
     * For the window's preview hook, which sees every key before anything focused
     * does, a key meant for a dialog included. Never consumes.
     */
    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        if (!event.isEscape()) return false
        when (event.type) {
            KeyEventType.KeyDown -> {
                fresh = !held
                held = true
            }
            KeyEventType.KeyUp -> held = false
        }
        return false
    }

    /**
     * For the window's key hook, which sees a key after everything focused has had
     * it and before the platform turns an unused Escape into back: the window's back
     * input on the desktop, the system's fallback key on Android.
     *
     * Takes the repeats of a held Escape and the half of a press the host used, so
     * one press never closes two things. A first press is left alone, and goes on to
     * back, which closes the topmost thing that listens for it: a popup or a dialog
     * opened after the host, or else this stack's top entry.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!event.isEscape()) return false
        return when (event.type) {
            KeyEventType.KeyDown -> pressTaken || !fresh
            KeyEventType.KeyUp -> pressTaken.also { pressTaken = false }
            else -> false
        }
    }

    /**
     * Escape on its way out of the host's focused content, its layers included. A
     * Compose popup or dialog sends its keys through its own owner and never comes
     * here. Takes the keyboard out of a field of the window's own content and keeps
     * the press from becoming back. A field in a layer keeps it, so the press closes
     * that layer as it would close a popup. So does a field under a modal, a list hung
     * off it for one: what the modal holds closes first. So does a field that always
     * holds the keyboard ([persistentInput]): the press goes on to the stack.
     */
    internal fun onHostKeyEvent(event: KeyEvent): Boolean {
        if (!event.isEscape() || event.type != KeyEventType.KeyDown) return false
        if (!fresh || pressTaken || !contentFieldFocused || layers.plan.modal != null) return false
        if (persistentFields.get() > 0) return false
        leaveField()
        pressTaken = true
        return true
    }

    /** Forgets a press in progress, for a window that lost the focus and with it the release. */
    internal fun resetKeys() {
        held = false
        fresh = true
        pressTaken = false
    }
}

/**
 * Escape, with a modifier held or not. The window's back input turns every Escape
 * into back whatever is held with it, so the field step and the held-key guard have
 * to see every one too, or a held Ctrl and Escape closes one thing per repeat.
 */
private fun KeyEvent.isEscape(): Boolean = key == Key.Escape

/**
 * Puts [onDismiss] on the window's dismiss stack while [enabled], for something
 * this composition opened: Escape and back close it from the top.
 *
 * The entry is stamped when [enabled] turns true, so of two things open at once the
 * one opened last closes first. Outside a [LayerHost] it does nothing.
 */
@Composable
fun DismissOnBack(enabled: Boolean = true, onDismiss: () -> Unit) {
    val stack = LocalWindowLayers.current?.dismiss ?: return
    val owner = LocalResident.current
    val current by rememberUpdatedState(onDismiss)
    DisposableEffect(stack, owner, enabled) {
        val handle = if (enabled) stack.push(owner) { current() } else null
        onDispose { handle?.close() }
    }
}
