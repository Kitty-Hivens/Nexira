package hivens.ui.background

import dev.hivens.skinema.libav.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in (NEXIRA_TEST_TRANSCODE=1) end-to-end check against a real on-disk 4K
 * video: the full optimize() path (skinema decode -> box-downscale -> VAAPI
 * encode) must produce a decodable display-height file on real natives.
 */
class BackgroundOptimizerIntegrationTest {

    @Test
    fun `optimizes the real 4K wallpaper to display height`() {
        // Opt-in: point NEXIRA_TEST_VIDEO at a tall on-disk video to exercise it.
        val path = System.getenv("NEXIRA_TEST_VIDEO") ?: return
        val src = Path.of(path)
        if (!Files.exists(src)) return

        val cacheDir = Files.createTempDirectory("bgopt-it")
        val started = System.nanoTime()
        val out = runBlocking { BackgroundOptimizer(cacheDir, CoroutineScope(Dispatchers.IO)).optimize(src, 1440) }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0

        val srcSize = VideoDecoder.open(src).use { it.videoSize() }
        val outSize = VideoDecoder.open(out).use { d ->
            val s = d.videoSize()
            assertNotNull(d.nextFrame(), "the optimized file must decode at least one frame")
            s
        }
        println("[bgopt] $src $srcSize -> $out $outSize in ${"%.1f".format(seconds)}s, ${Files.size(out)} bytes")

        assertTrue(out != src, "an oversized source must be transcoded, not returned as-is")
        assertTrue((outSize?.second ?: Int.MAX_VALUE) <= 1440, "output must be no taller than 1440")
    }
}
