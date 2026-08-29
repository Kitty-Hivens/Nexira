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

    @Test
    fun `Escape backs out of the editor, but only while one is open`() {
        val editing = resolveChord(Key.Escape, ctrl = false, released = true, debugOverlayAvailable = false, editing = true)
        assertEquals(ShellChord.ExitEditor, editing.chord)
        assertTrue(editing.consume)

        // Escape is how every dialog, popup and overlay in the shell closes, so
        // claiming it at window scope with no editor open would take it from all of
        // them -- the same gate F9 gets, for a much more crowded key.
        val idle = resolveChord(Key.Escape, ctrl = false, released = true, debugOverlayAvailable = false, editing = false)
        assertNull(idle.chord)
        assertFalse(idle.consume, "with no editor open Escape belongs to whatever is on screen")
    }

    @Test
    fun `the Escape press edge is swallowed but does nothing`() {
        val down = resolveChord(Key.Escape, ctrl = false, released = false, debugOverlayAvailable = false, editing = true)
        assertNull(down.chord)
        assertTrue(down.consume, "the down edge must not reach whatever holds focus")
    }

    @Test
    fun `an unrelated chord is left alone`() {
        val resolved = resolveChord(Key.S, ctrl = true, released = true, debugOverlayAvailable = true, editing = false)
        assertNull(resolved.chord)
        assertFalse(resolved.consume)
    }
}
