package hivens.ui.bootstrap

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit-tests the keymap bit math (the off-by-one-prone part). The live libX11
 * path is verified out of band under Xvfb with a synthetic XTEST key hold.
 */
class HoldKeyProbeTest {

    private fun keymapWith(keycode: Int): ByteArray =
        ByteArray(32).also { it[keycode / 8] = (1 shl (keycode % 8)).toByte() }

    @Test
    fun `detects a set keycode bit`() {
        assertTrue(HoldKeyProbe.shiftHeldInKeymap(keymapWith(50), listOf(50, 62)))
        assertTrue(HoldKeyProbe.shiftHeldInKeymap(keymapWith(62), listOf(50, 62)))
    }

    @Test
    fun `false when no requested keycode is down`() {
        assertFalse(HoldKeyProbe.shiftHeldInKeymap(keymapWith(9), listOf(50, 62)))
        assertFalse(HoldKeyProbe.shiftHeldInKeymap(ByteArray(32), listOf(50, 62)))
    }

    @Test
    fun `detects the high bit of a keymap byte`() {
        // keycode 63 = byte 7, bit 7 -- guards the signed-Byte sign-extension.
        assertTrue(HoldKeyProbe.shiftHeldInKeymap(keymapWith(63), listOf(63)))
    }

    @Test
    fun `ignores out-of-range keycodes`() {
        assertFalse(HoldKeyProbe.shiftHeldInKeymap(ByteArray(32), listOf(0, 256, -1)))
    }
}
