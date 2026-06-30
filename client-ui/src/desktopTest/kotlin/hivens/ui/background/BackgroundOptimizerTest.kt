package hivens.ui.background

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BackgroundOptimizerTest {

    @Test
    fun `identity size returns the same array untouched`() {
        val src = ByteArray(2 * 2 * 4) { it.toByte() }
        assertSame(src, boxDownscale(src, 2, 2, 2, 2))
    }

    @Test
    fun `a 2x2 block averages to one pixel`() {
        val src = ByteArray(2 * 2 * 4)
        fun put(i: Int, r: Int) {
            src[i * 4] = r.toByte()
            src[i * 4 + 3] = 255.toByte()
        }
        put(0, 0); put(1, 40); put(2, 80); put(3, 120)

        val out = boxDownscale(src, 2, 2, 1, 1)

        assertEquals(4, out.size)
        assertEquals(60, out[0].toInt() and 0xFF, "R is the mean of 0,40,80,120")
        assertEquals(255, out[3].toInt() and 0xFF, "opaque alpha survives")
    }

    @Test
    fun `output is exactly the requested geometry`() {
        val out = boxDownscale(ByteArray(8 * 6 * 4), 8, 6, 4, 3)
        assertEquals(4 * 3 * 4, out.size)
    }

    @Test
    fun `a solid colour round-trips through any ratio`() {
        val src = ByteArray(5 * 5 * 4) { if (it % 4 == 3) 255.toByte() else 17.toByte() }
        val out = boxDownscale(src, 5, 5, 2, 2)
        for (p in 0 until 2 * 2) {
            assertEquals(17, out[p * 4].toInt() and 0xFF, "a flat field stays flat after the box filter")
        }
    }
}
