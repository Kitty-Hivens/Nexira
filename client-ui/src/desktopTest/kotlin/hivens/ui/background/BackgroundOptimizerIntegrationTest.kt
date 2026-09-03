package hivens.ui.background

import dev.hivens.skinema.libav.VideoDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in (NEXIRA_TEST_TRANSCODE=1) end-to-end check against a real on-disk 4K
 * video: the full optimize() path (skinema decode -> box-downscale -> VAAPI
 * encode) must produce a decodable display-height file on real natives.
 */
class BackgroundOptimizerIntegrationTest {

    /**
     * The other half of the classification, which needs a real container: a file
     * Skia cannot read and that has a second frame is time-based. The still
     * formats the decoder also reads are pinned without natives in
     * [BackgroundMediaKindTest].
     */
    @Test
    fun `a real video is time-based`() {
        val path = System.getenv("NEXIRA_TEST_VIDEO") ?: return
        val src = Path.of(path)
        if (!Files.exists(src)) return

        assertEquals(BackgroundMediaKind.TimeBased, backgroundMediaKind(src.toFile()))
    }

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

    /**
     * The transcode must end when it is cancelled, not when the file runs out.
     * The target height is deliberately small so any source qualifies, and the
     * join timeout is the assertion: an encode loop without a cancellation point
     * ignores the cancel and keeps a core for the length of the video.
     */
    @Test
    fun `a cancelled transcode stops within seconds and leaves nothing behind`() {
        val path = System.getenv("NEXIRA_TEST_VIDEO") ?: return
        val src = Path.of(path)
        if (!Files.exists(src)) return

        val cacheDir = Files.createTempDirectory("bgopt-cancel")
        val optimizer = BackgroundOptimizer(cacheDir, CoroutineScope(Dispatchers.IO))

        runBlocking {
            val caller = launch { runCatching { optimizer.optimize(src, 480) } }
            // Let the encoder actually get going before pulling the rug out.
            withTimeout(30_000) { optimizer.optimizing.first { it != null } }
            delay(2_000)

            optimizer.cancel()
            withTimeout(10_000) { caller.join() }
            withTimeout(10_000) { optimizer.optimizing.first { it == null } }
        }

        val leftovers = cacheDir.toFile().listFiles()?.map { it.name }.orEmpty()
        assertTrue(leftovers.isEmpty(), "a cancelled transcode publishes no output and keeps no .part: $leftovers")
    }
}
