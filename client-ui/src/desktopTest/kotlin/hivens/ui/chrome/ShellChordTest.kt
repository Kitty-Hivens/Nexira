package hivens.ui.chrome

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The window chords, which decide two things that are easy to conflate: what a
 * press means, and whether the event is swallowed. A claimed chord is consumed
 * on both edges so its other half never reaches a focused control, but it acts
 * only on release -- auto-repeat delivers a stream of key-downs, and acting on
 * those would toggle edit mode for as long as the key is held.
 */
class ShellChordTest {

    @Test
    fun `ctrl E toggles edit mode on release`() {
        val resolved = resolveChord(Key.E, ctrl = true, released = true, debugOverlayAvailable = false, editing = false)
        assertEquals(ShellChord.ToggleEditMode, resolved.chord)
        assertTrue(resolved.consume)
    }

    @Test
    fun `the press edge is swallowed but does nothing`() {
        val resolved = resolveChord(Key.E, ctrl = true, released = false, debugOverlayAvailable = false, editing = false)
        assertNull(resolved.chord, "acting on the press would repeat the toggle under auto-repeat")
        assertTrue(resolved.consume, "the down edge must not reach whatever holds focus")
    }

    @Test
    fun `ctrl N toggles the right rail`() {
        assertEquals(
            ShellChord.ToggleRightRail,
            resolveChord(Key.N, ctrl = true, released = true, debugOverlayAvailable = false, editing = false).chord,
        )
    }

    @Test
    fun `the same keys without ctrl are ordinary typing`() {
        val resolved = resolveChord(Key.E, ctrl = false, released = true, debugOverlayAvailable = false, editing = false)
        assertNull(resolved.chord)
        assertFalse(resolved.consume, "swallowing a bare E would break every text field in the shell")
    }

    @Test
    fun `F9 is claimed only where an overlay exists`() {
        assertEquals(
            ShellChord.ToggleDebugOverlay,
            resolveChord(Key.F9, ctrl = false, released = true, debugOverlayAvailable = true, editing = false).chord,
        )

        val onRelease = resolveChord(Key.F9, ctrl = false, released = true, debugOverlayAvailable = false, editing = false)
        assertNull(onRelease.chord)
        assertFalse(onRelease.consume, "a release build has no overlay, so the key belongs to whoever else wants it")
    }

    // The editor's back-out steps are entries of the window's dismiss stack now. A
    // chord sees the key before anything focused does, so claiming Escape here, even
    // while editing, would take it from a field, a dialog and the stack alike.
    @Test
    fun `Escape is never a chord, with the editor open or not`() {
        for (editing in listOf(true, false)) {
            for (released in listOf(true, false)) {
                val resolved = resolveChord(Key.Escape, ctrl = false, released = released, debugOverlayAvailable = true, editing = editing)
                assertNull(resolved.chord)
                assertFalse(resolved.consume, "Escape goes on to whatever is focused and then to the dismiss stack")
            }
        }
    }

    @Test
    fun `ctrl Z undoes, and with shift redoes, while the editor is open`() {
        val undo = resolveChord(Key.Z, ctrl = true, released = true, debugOverlayAvailable = false, editing = true)
        assertEquals(ShellChord.UndoEdit, undo.chord)
        assertTrue(undo.consume)

        val redo = resolveChord(Key.Z, ctrl = true, shift = true, released = true, debugOverlayAvailable = false, editing = true)
        assertEquals(ShellChord.RedoEdit, redo.chord)
    }

    @Test
    fun `ctrl Z belongs to the focused field with no editor open`() {
        val idle = resolveChord(Key.Z, ctrl = true, released = true, debugOverlayAvailable = false, editing = false)
        assertNull(idle.chord)
        assertFalse(idle.consume, "taking Ctrl+Z at window scope would take it from every text field in the shell")
    }

    // The preset dialog's name field: Ctrl+Z undoes the typing, not the layout
    // behind the dialog, and a preset saved next carries the layout as it was.
    @Test
    fun `ctrl Z belongs to a focused field while the editor is open`() {
        for (shift in listOf(false, true)) {
            for (released in listOf(false, true)) {
                val resolved = resolveChord(
                    Key.Z, ctrl = true, shift = shift, released = released,
                    debugOverlayAvailable = false, editing = true, fieldFocused = true,
                )
                assertNull(resolved.chord)
                assertFalse(resolved.consume, "the field gets both edges")
            }
        }
    }

    @Test
    fun `under a modal the chords that act behind it are left to it`() {
        for (key in listOf(Key.E, Key.N, Key.Z)) {
            for (released in listOf(false, true)) {
                val resolved = resolveChord(
                    key, ctrl = true, released = released,
                    debugOverlayAvailable = true, editing = true, modalOpen = true,
                )
                assertNull(resolved.chord, "$key")
                assertFalse(resolved.consume, "$key")
            }
        }
        assertEquals(
            ShellChord.ToggleDebugOverlay,
            resolveChord(Key.F9, ctrl = false, released = true, debugOverlayAvailable = true, editing = false, modalOpen = true).chord,
            "the debug overlay is a diagnostic and works over a modal",
        )
    }

    @Test
    fun `a focused field leaves edit mode and the rail chords working`() {
        assertEquals(
            ShellChord.ToggleEditMode,
            resolveChord(Key.E, ctrl = true, released = true, debugOverlayAvailable = false, editing = false, fieldFocused = true).chord,
        )
        assertEquals(
            ShellChord.ToggleRightRail,
            resolveChord(Key.N, ctrl = true, released = true, debugOverlayAvailable = false, editing = true, fieldFocused = true).chord,
        )
    }

    @Test
    fun `an unrelated chord is left alone`() {
        val resolved = resolveChord(Key.S, ctrl = true, released = true, debugOverlayAvailable = true, editing = false)
        assertNull(resolved.chord)
        assertFalse(resolved.consume)
    }
}
