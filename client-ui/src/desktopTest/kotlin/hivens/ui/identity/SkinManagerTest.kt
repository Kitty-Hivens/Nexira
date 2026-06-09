package hivens.ui.identity

import hivens.core.api.HttpClientProvider
import hivens.core.time.Clock
import hivens.launcher.platform.PlatformPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class SkinManagerTest {

    private lateinit var home: Path

    // Routes the data dir at the temp home (via the NEXIRA_DATA_DIR env hook)
    // so skinCacheDir is a temp subdir and never the real user cache.
    private fun paths() = PlatformPaths(
        "Linux", home,
        { null },
        { key -> if (key == "NEXIRA_DATA_DIR") home.toString() else null },
    )

    // A valid 64x64 PNG so getRawSkin's disk path decodes a real texture.
    private fun tinyPng(): ByteArray {
        val bmp = org.jetbrains.skia.Bitmap()
        bmp.allocPixels(org.jetbrains.skia.ImageInfo.makeS32(64, 64, org.jetbrains.skia.ColorAlphaType.PREMUL))
        bmp.erase(0xFF202020.toInt())
        return org.jetbrains.skia.Image.makeFromBitmap(bmp)
            .encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
    }

    @BeforeTest
    fun setUp() {
        home = Files.createTempDirectory("skin-manager-test-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        home.deleteRecursively()
    }

    @Test
    fun `isExpired flips once the injected clock passes the TTL`() {
        var now = 1_000_000L
        val paths = PlatformPaths("Linux", home, { null }, { null })
        // The http client is never touched by isExpired, so a lazily-erroring
        // provider is enough and keeps this off the network.
        val provider = HttpClientProvider { error("SkinManager http client not needed here") }
        val manager = SkinManager(provider, paths, Clock { now })

        val cached = File(home.toFile(), "front_test.png").apply {
            writeBytes(ByteArray(4))
            setLastModified(1_000_000L)
        }

        assertFalse(manager.isExpired(cached), "a just-written file is fresh")
        now += 31 * 60 * 1000L // TTL is 30 minutes
        assertTrue(manager.isExpired(cached), "past the TTL it must read as expired")
    }

    @Test
    fun `getRawSkin serves a fresh raw cache from disk without the network`() = runBlocking {
        val paths = paths()
        // Erroring provider proves the disk-cache path never touches the network.
        val manager = SkinManager(HttpClientProvider { error("network must not be hit") }, paths)
        val cacheDir = paths.skinCacheDir.toFile().apply { mkdirs() }
        File(cacheDir, "raw_steve.png").writeBytes(tinyPng())

        val bitmap = manager.getRawSkin("steve")
        assertNotNull(bitmap, "a fresh raw cache must decode to a bitmap")
        assertEquals(64, bitmap.width)
        assertEquals(64, bitmap.height)
    }

    @Test
    fun `getRawSkin returns null when the texture cannot be fetched`() = runBlocking {
        // No cache + a provider whose client access throws -> downloadTexture
        // swallows it and getRawSkin yields null rather than crashing the UI.
        val manager = SkinManager(HttpClientProvider { error("download fails") }, paths())
        assertNull(manager.getRawSkin("nobody"))
    }
}
