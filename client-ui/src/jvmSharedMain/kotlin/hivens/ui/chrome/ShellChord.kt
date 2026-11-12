package hivens.ui.chrome

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** A window-scoped keyboard chord, resolved from a raw key event. */
enum class ShellChord {
    /** Ctrl+E -- widget edit mode. */
    ToggleEditMode,

    /** Ctrl+N -- collapse or expand the right rail. */
    ToggleRightRail,

    /** F9 -- the dev UI-debug overlay. Only claimed on a build that has one. */
    ToggleDebugOverlay,

    /** Escape: back out of widget edit mode. Only claimed while an editor is open
     *  with none of its dialogs up, so every dialog and popup keeps its own Escape. */
    ExitEditor,

    /** Ctrl+Z -- take back the last edit. Claimed only while an editor is open. */
    UndoEdit,

    /** Ctrl+Shift+Z -- put back what an undo took. Same gate as [UndoEdit]. */
    RedoEdit,
}

/**
 * What a chord press means, and whether it should be swallowed.
 *
 * [chord] is non-null only on the release edge: acting on press would repeat
 * the toggle for as long as the key is held, since auto-repeat delivers a
 * stream of KeyDowns. [consume] is true on both edges regardless, so the other
 * half of a claimed chord never reaches a focused control -- a Ctrl+N that
 * toggles the rail on release must not also type into whatever has focus on
 * the way down.
 *
 * Resolved here rather than in the shell's key handler because "claimed but
 * not acted on" is exactly the case a reader gets wrong, and it had no test
 * while it lived inside a `when` in a 1400-line composable.
 */
data class ChordResolution(val chord: ShellChord?, val consume: Boolean) {
    companion object {
        val Ignored = ChordResolution(chord = null, consume = false)
    }
}

/**
 * Whether the window takes Escape, decided once for a press and its release.
 *
 * A dialog closes on the press of Escape and the window acts on the release, so a
 * release judged on its own found the editor ready again behind the dialog it had
 * just closed and backed it out a step as well: one press, two things done. The
 * release is taken only when its press was. [open] says whether the window may
 * take Escape at all right now.
 */
class EscapeClaim(private val open: () -> Boolean) {
    private var pressTaken = false

    /** Whether this edge of Escape is the window's. [released] tells the release from the press. */
    fun takes(released: Boolean): Boolean =
        if (released) {
            pressTaken.also { pressTaken = false }
        } else {
            open().also { pressTaken = it }
        }
}

/**
 * Resolves [event] against the window-scoped chords. A thin read of the event;
 * the decision itself is [resolveChord], which takes no Compose types so it can
 * be tested without fabricating a native key event.
 */
fun resolveShellChord(
    event: KeyEvent,
    debugOverlayAvailable: Boolean,
    editing: Boolean,
    escape: EscapeClaim,
): ChordResolution {
    val released = event.type == KeyEventType.KeyUp
    return resolveChord(
        key = event.key,
        ctrl = event.isCtrlPressed,
        shift = event.isShiftPressed,
        released = released,
        debugOverlayAvailable = debugOverlayAvailable,
        editing = editing,
        // Asked of Escape alone, because asking is what pairs a release with its press.
        claimEscape = event.key == Key.Escape && escape.takes(released),
    )
}

/**
 * The decision behind [resolveShellChord].
 *
 * [debugOverlayAvailable] gates F9: a release build has no overlay, so the key
 * is left to whoever else wants it rather than silently eaten. [claimEscape]
 * gates Escape the same way, and it matters far more there: Escape is how every
 * dialog, popup and overlay in the launcher closes, so claiming it at window scope
 * while no editor is open would take it from all of them. It is narrower than
 * [editing], which gates Ctrl+Z: an editor with one of its own dialogs open leaves
 * Escape to the dialog. Left out, it follows [editing].
 */
internal fun resolveChord(
    key: Key,
    ctrl: Boolean,
    // Only one chord reads it, so the callers that predate it say what they meant
    // by leaving it out rather than by repeating `shift = false` eight times.
    shift: Boolean = false,
    released: Boolean,
    debugOverlayAvailable: Boolean,
    editing: Boolean,
    claimEscape: Boolean = editing,
): ChordResolution = when {
    ctrl && key == Key.E -> ChordResolution(ShellChord.ToggleEditMode.takeIf { released }, consume = true)
    ctrl && key == Key.N -> ChordResolution(ShellChord.ToggleRightRail.takeIf { released }, consume = true)
    debugOverlayAvailable && key == Key.F9 ->
        ChordResolution(ShellChord.ToggleDebugOverlay.takeIf { released }, consume = true)
    claimEscape && !ctrl && key == Key.Escape ->
        ChordResolution(ShellChord.ExitEditor.takeIf { released }, consume = true)
    // Gated on [editing] for the reason Escape is: Ctrl+Z belongs to whatever a
    // person is typing in the rest of the time, and taking it at window scope
    // would take it from every field in the launcher.
    editing && ctrl && key == Key.Z ->
        ChordResolution(
            (if (shift) ShellChord.RedoEdit else ShellChord.UndoEdit).takeIf { released },
            consume = true,
        )
    else -> ChordResolution.Ignored
}
