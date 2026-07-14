package hivens.ui.background

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackgroundOptimizerTest {

    @Test
    fun `identity size returns the same array untouched`() {
        val src = ByteArray(2 * 2 * 4) { it.toByte() }
        assertSame(src, scaleRgba(src, 2, 2, 2, 2))
    }

    @Test
    fun `output is exactly the requested geometry`() {
        val out = scaleRgba(ByteArray(8 * 6 * 4) { (-1).toByte() }, 8, 6, 4, 3)
        assertEquals(4 * 3 * 4, out.size)
    }

    @Test
    fun `a solid opaque colour survives the resample`() {
        // Opaque mid-grey (RGB 0x40, A 0xFF) everywhere.
        val src = ByteArray(8 * 8 * 4) { if (it % 4 == 3) (-1).toByte() else 0x40.toByte() }
        val out = scaleRgba(src, 8, 8, 4, 4)
        val center = (1 * 4 + 1) * 4
        assertTrue((out[center].toInt() and 0xFF) in 0x38..0x48, "a flat field stays ~constant through LINEAR")
        assertEquals(0xFF, out[center + 3].toInt() and 0xFF, "opaque alpha survives")
    }
}
