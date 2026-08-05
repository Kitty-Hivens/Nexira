package hivens.ui.background

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BackgroundOptimizerTest {

    @Test
    fun `the sweep keeps the wallpaper in use and drops the rest`() {
        val dir = Files.createTempDirectory("bgopt-sweep")
        val keep    = touch(dir, "keep.mp4")
        val stale   = touch(dir, "stale.mp4")
        val running = touch(dir, "half-written.part.mp4")
        val still   = touch(dir, "img-0123456789abcdef-fedcba9876543210.png")

        sweepTranscodes(dir, keep)

        assertTrue(Files.exists(keep), "the transcode the wallpaper points at stays")
        assertFalse(Files.exists(stale), "a transcode of a wallpaper no longer set goes")
        assertTrue(Files.exists(running), "a transcode still being written is left alone")
        assertTrue(Files.exists(still), "the still-image cache is not ours to evict")
    }

    @Test
    fun `the sweep clears every transcode when no wallpaper is set`() {
        val dir = Files.createTempDirectory("bgopt-sweep-none")
        val one = touch(dir, "one.mp4")
        val two = touch(dir, "two.mp4")

        sweepTranscodes(dir, keep = null)

        assertFalse(Files.exists(one))
        assertFalse(Files.exists(two))
    }

    @Test
    fun `a headless display height leaves the source untouched`() {
        val dir = Files.createTempDirectory("bgopt-headless")
        val src = touch(Files.createTempDirectory("bgopt-headless-src"), "wallpaper.mp4")
        val optimizer = BackgroundOptimizer(dir, CoroutineScope(Dispatchers.IO))

        // physicalScreenHeight() reports 0 with no display; the transcode must
        // then be skipped outright rather than run against a zero target.
        assertSame(src, runBlocking { optimizer.optimize(src, maxHeight = 0) })
    }

    private fun touch(dir: Path, name: String): Path = Files.write(dir.resolve(name), byteArrayOf(1))

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
