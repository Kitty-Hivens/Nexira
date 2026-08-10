package hivens.ui.chrome

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
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
 * Resolves [event] against the window-scoped chords. A thin read of the event;
 * the decision itself is [resolveChord], which takes no Compose types so it can
 * be tested without fabricating a native key event.
 */
fun resolveShellChord(event: KeyEvent, debugOverlayAvailable: Boolean): ChordResolution =
    resolveChord(
        key = event.key,
        ctrl = event.isCtrlPressed,
        released = event.type == KeyEventType.KeyUp,
        debugOverlayAvailable = debugOverlayAvailable,
    )

/**
 * The decision behind [resolveShellChord].
 *
 * [debugOverlayAvailable] gates F9: a release build has no overlay, so the key
 * is left to whoever else wants it rather than silently eaten.
 */
internal fun resolveChord(
    key: Key,
    ctrl: Boolean,
    released: Boolean,
    debugOverlayAvailable: Boolean,
): ChordResolution = when {
    ctrl && key == Key.E -> ChordResolution(ShellChord.ToggleEditMode.takeIf { released }, consume = true)
    ctrl && key == Key.N -> ChordResolution(ShellChord.ToggleRightRail.takeIf { released }, consume = true)
    debugOverlayAvailable && key == Key.F9 ->
        ChordResolution(ShellChord.ToggleDebugOverlay.takeIf { released }, consume = true)
    else -> ChordResolution.Ignored
}
