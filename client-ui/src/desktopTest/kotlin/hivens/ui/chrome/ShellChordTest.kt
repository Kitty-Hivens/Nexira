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
        val resolved = resolveChord(Key.E, ctrl = true, released = true, debugOverlayAvailable = false)
        assertEquals(ShellChord.ToggleEditMode, resolved.chord)
        assertTrue(resolved.consume)
    }

    @Test
    fun `the press edge is swallowed but does nothing`() {
        val resolved = resolveChord(Key.E, ctrl = true, released = false, debugOverlayAvailable = false)
        assertNull(resolved.chord, "acting on the press would repeat the toggle under auto-repeat")
        assertTrue(resolved.consume, "the down edge must not reach whatever holds focus")
    }

    @Test
    fun `ctrl N toggles the right rail`() {
        assertEquals(
            ShellChord.ToggleRightRail,
            resolveChord(Key.N, ctrl = true, released = true, debugOverlayAvailable = false).chord,
        )
    }

    @Test
    fun `the same keys without ctrl are ordinary typing`() {
        val resolved = resolveChord(Key.E, ctrl = false, released = true, debugOverlayAvailable = false)
        assertNull(resolved.chord)
        assertFalse(resolved.consume, "swallowing a bare E would break every text field in the shell")
    }

    @Test
    fun `F9 is claimed only where an overlay exists`() {
        assertEquals(
            ShellChord.ToggleDebugOverlay,
            resolveChord(Key.F9, ctrl = false, released = true, debugOverlayAvailable = true).chord,
        )

        val onRelease = resolveChord(Key.F9, ctrl = false, released = true, debugOverlayAvailable = false)
        assertNull(onRelease.chord)
        assertFalse(onRelease.consume, "a release build has no overlay, so the key belongs to whoever else wants it")
    }

    @Test
    fun `an unrelated chord is left alone`() {
        val resolved = resolveChord(Key.S, ctrl = true, released = true, debugOverlayAvailable = true)
        assertNull(resolved.chord)
        assertFalse(resolved.consume)
    }
}
