package hivens.ui.background

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The still-wallpaper disk cache: an oversized source is downscaled to the display
 * height once, cached, and re-read on later loads; a source already within the
 * display height is decoded directly and left uncached.
 */
class BackgroundImageCacheTest {

    private val tmp = Files.createTempDirectory("bg-cache-test").toFile()
    private val cacheDir = File(tmp, "background-cache")

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun cachedPngs(): List<File> =
        cacheDir.listFiles { f -> f.name.startsWith("img-") && f.name.endsWith(".png") }?.toList() ?: emptyList()

    /** Write a solid opaque PNG of [w]x[h] to [tmp]. */
    private fun writePng(name: String, w: Int, h: Int): File {
        val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(w, h)); erase(0xFF3366AA.toInt()) }
        val bytes = bmp.use {
            Image.makeFromBitmap(bmp).use { img ->
                img.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
            }
        }
        return File(tmp, name).apply { writeBytes(bytes) }
    }

    @Test
    fun oversizedSourceIsDownscaledAndCached() {
        val src = writePng("tall.png", 40, 2000)

        val bmp = loadStaticBackground(src, cacheDir, maxHeight = 1000)

        assertNotNull(bmp, "decode must succeed")
        assertEquals(1000, bmp.height, "returned bitmap is downscaled to the display height")
        assertEquals(20, bmp.width, "aspect ratio is preserved (40x2000 -> 20x1000)")
        assertEquals(1, cachedPngs().size, "the downscaled copy is cached")
    }

    @Test
    fun secondLoadHitsTheCacheWithoutMintingAnotherFile() {
        val src = writePng("tall.png", 40, 2000)

        loadStaticBackground(src, cacheDir, 1000)
        val afterFirst = cachedPngs().single()
        val bmp = loadStaticBackground(src, cacheDir, 1000)

        assertNotNull(bmp)
        assertEquals(1000, bmp.height)
        assertEquals(listOf(afterFirst.name), cachedPngs().map { it.name }, "no second cache file is created")
    }

    @Test
    fun sourceWithinDisplayHeightIsNotCached() {
        val src = writePng("small.png", 40, 800)

        val bmp = loadStaticBackground(src, cacheDir, maxHeight = 1000)

        assertNotNull(bmp)
        assertEquals(800, bmp.height, "a source no taller than the display decodes at full size")
        assertTrue(cachedPngs().isEmpty(), "a source that needs no downscale is not cached")
    }

    @Test
    fun editingTheSourceEvictsTheStaleCopy() {
        val src = writePng("tall.png", 40, 2000)
        loadStaticBackground(src, cacheDir, 1000)
        val first = cachedPngs().single().name

        // A different mtime keys a new variant; the older copy for the same source is dropped.
        src.setLastModified(src.lastModified() + 10_000)
        loadStaticBackground(src, cacheDir, 1000)

        val now = cachedPngs()
        assertEquals(1, now.size, "only one cached copy per source survives")
        assertTrue(now.single().name != first, "the fresh variant replaced the stale one")
    }

    @Test
    fun zeroMaxHeightDecodesDirectlyWithoutCaching() {
        val src = writePng("tall.png", 40, 2000)

        val bmp = loadStaticBackground(src, cacheDir, maxHeight = 0)

        assertNotNull(bmp, "headless fallback still decodes")
        assertEquals(2000, bmp.height, "no downscale when the display height is unknown")
        assertTrue(cachedPngs().isEmpty())
    }
}
