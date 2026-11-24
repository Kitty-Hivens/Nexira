package hivens.ui.pictures

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import hivens.ui.FRAME_NANOS
import hivens.ui.onEventThread
import hivens.ui.pictures.PictureServer.Companion.RED
import hivens.ui.pictures.PictureServer.Companion.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.jetbrains.skia.Bitmap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What stands where a picture will be, over a server that answers, answers late or has nothing. */
@OptIn(ExperimentalComposeUiApi::class, DelicateCoilApi::class)
class PictureTest {

    private val dir: Path = Files.createTempDirectory("picture")
    private val server = PictureServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient()
    private lateinit var loader: ImageLoader

    @BeforeTest
    fun setUp() {
        loader = pictureLoader(PlatformContext.INSTANCE, dir, client, PictureRechecks(scope))
        SingletonImageLoader.setUnsafe(loader)
    }

    @AfterTest
    fun tearDown() {
        SingletonImageLoader.reset()
        scope.cancel()
        loader.shutdown()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.close()
        dir.toFile().deleteRecursively()
    }

    /** The middle of a 40 dp picture after each of [frames] frames. */
    private fun frames(url: String?, frames: Int, sleepMs: Long = 0): List<Int> = onEventThread {
        val scene = ImageComposeScene(40, 40, Density(1f)) {
            Picture(url, Modifier.size(40.dp)) { Box(Modifier.matchParentSize().background(Color.Green)) }
        }
        try {
            (0 until frames).map { n ->
                val image = scene.render(n * FRAME_NANOS)
                if (sleepMs > 0) Thread.sleep(sleepMs)
                Bitmap.makeFromImage(image).getColor(20, 20) and 0xFFFFFF
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the placeholder stands until the picture is there`() {
        server.answer = { Thread.sleep(150); it.send(200, png(RED, edge = 64)) }
        val seen = frames(server.url(), frames = 60, sleepMs = 20)
        assertEquals(GREEN, seen.first())
        assertEquals(RED, seen.last())
        assertEquals(setOf(GREEN, RED), seen.toSet(), "nothing stands between the two")
    }

    @Test
    fun `the placeholder stays where the picture is gone`() {
        server.answer = { it.send(404, "gone".toByteArray()) }
        assertEquals(setOf(GREEN), frames(server.url(), frames = 30, sleepMs = 10).toSet())
        assertTrue(server.asked.isNotEmpty(), "the server was asked")
    }

    @Test
    fun `no address and a blank one are the placeholder`() {
        assertEquals(setOf(GREEN), frames(null, frames = 3).toSet())
        assertEquals(setOf(GREEN), frames("", frames = 3).toSet())
        assertEquals(0, server.asked.size)
    }

    @Test
    fun `a warmed picture is on the first frame`() {
        server.answer = { it.send(200, png(RED, edge = 64)) }
        loader.warm(PlatformContext.INSTANCE, server.url(), 40, 40)
        val end = System.currentTimeMillis() + 10_000
        while (loader.memoryCache?.keys.orEmpty().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertEquals(listOf(RED, RED, RED), frames(server.url(), frames = 3))
    }

    private companion object {
        const val GREEN = 0x00FF00
    }
}
