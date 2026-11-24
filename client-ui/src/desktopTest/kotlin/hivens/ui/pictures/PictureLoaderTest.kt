package hivens.ui.pictures

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.decode.DataSource
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Precision
import coil3.size.Scale
import coil3.toBitmap
import hivens.ui.pictures.PictureServer.Companion.BLUE
import hivens.ui.pictures.PictureServer.Companion.RED
import hivens.ui.pictures.PictureServer.Companion.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Each test draws a picture in one run of the launcher and looks at what the next run does with
 * the copy on disk: [run] is a loader with an empty memory over the same directory.
 */
class PictureLoaderTest {

    private val dir: Path = Files.createTempDirectory("pictures")
    private val server = PictureServer()
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var settled = true
    private val client = OkHttpClient()
    private val opened = ArrayList<ImageLoader>()
    private var runs = 0

    @AfterTest
    fun tearDown() {
        scope.cancel()
        opened.forEach { it.shutdown() }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.close()
        dir.toFile().deleteRecursively()
    }

    /** A new run over what the last one left on disk, whose directory one open cache holds at a time. */
    private fun run(settle: Duration = 300.milliseconds, every: Duration = 1.hours): ImageLoader {
        endRun()
        val before = dir.resolve("run$runs")
        val now = dir.resolve("run${++runs}")
        if (Files.isDirectory(before)) before.toFile().copyRecursively(now.toFile())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settled = settle < 1.hours
        return pictureLoader(PlatformContext.INSTANCE, now, client, PictureRechecks(scope, settle, every)).also { opened += it }
    }

    /** Lets the questions under way land before the directory is copied: one half written is not kept. */
    private fun endRun() {
        if (settled) runBlocking { withTimeout(10.seconds) { scope.coroutineContext.job.children.forEach { it.join() } } }
        scope.cancel()
    }

    private fun draw(loader: ImageLoader, path: String = "/banner.png"): ImageResult = runBlocking {
        withTimeout(10.seconds) { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(server.url(path)).build()) }
    }

    private fun ImageResult.colour(): Int = assertIs<SuccessResult>(this).image.toBitmap().getColor(0, 0) and 0xFFFFFF

    private fun until(what: String, holds: () -> Boolean) = runBlocking {
        withTimeout(10.seconds) { while (!holds()) delay(10.milliseconds) }
        check(holds()) { what }
    }

    @Test
    fun `a kept picture is drawn without waiting for a server that is slow to answer`() {
        draw(run())
        val answered = CountDownLatch(1)
        server.answer = { answered.await(20, TimeUnit.SECONDS); it.send(200, png(BLUE)) }

        val drawn = draw(run())
        assertEquals(RED, drawn.colour())
        assertEquals(DataSource.DISK, assertIs<SuccessResult>(drawn).dataSource)
        until("the server is asked behind it") { server.asked.size == 2 }
        answered.countDown()
    }

    @Test
    fun `a picture that changed is drawn the next time it is asked for`() {
        draw(run())
        server.answer = { it.send(200, png(BLUE)) }

        val loader = run()
        assertEquals(RED, draw(loader).colour())
        until("the new picture is drawn in the same run") { draw(loader).colour() == BLUE }
        assertEquals(BLUE, draw(run()).colour(), "and by the run after")
    }

    @Test
    fun `a picture that has not changed costs a question with its tag and no more`() {
        server.answer = { it.send(200, png(RED), "ETag" to "\"v1\"") }
        draw(run())
        server.answer = { if (it.requestHeaders.getFirst("If-None-Match") == "\"v1\"") it.send(304) else it.send(200, png(BLUE)) }

        val loader = run()
        draw(loader)
        until("it is asked about") { server.asked.size == 2 }
        assertEquals("\"v1\"", server.asked.last().tag)
        assertEquals(RED, draw(loader).colour())
        assertEquals(RED, draw(run()).colour(), "kept under the answer that said so")
    }

    @Test
    fun `a kept picture is drawn when the server cannot be reached`() {
        draw(run())
        val address = server.url()
        server.close()
        val loader = run()
        val drawn = runBlocking { loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(address).build()) }
        assertEquals(RED, drawn.colour())
    }

    @Test
    fun `a server in trouble does not take the kept picture away`() {
        draw(run())
        server.answer = { it.send(503, "down".toByteArray()) }

        draw(run())
        until("it is asked about") { server.asked.size == 2 }
        val next = run(settle = 1.hours)
        assertEquals(RED, draw(next).colour())
    }

    @Test
    fun `a picture the server no longer has is let go`() {
        draw(run())
        server.answer = { it.send(404, "gone".toByteArray()) }

        val loader = run()
        assertEquals(RED, draw(loader).colour())
        until("it stops being drawn") { draw(loader) is ErrorResult }
    }

    @Test
    fun `a picture its server says to keep is not asked about`() {
        server.answer = { it.send(200, png(RED), "Cache-Control" to "public, max-age=3600") }
        draw(run())
        draw(run())
        runBlocking { delay(600.milliseconds) }
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `a picture drawn over and over is asked about once in a while`() {
        draw(run())
        val loader = run()
        repeat(5) {
            val _ = runBlocking {
                loader.execute(ImageRequest.Builder(PlatformContext.INSTANCE).data(server.url()).memoryCachePolicy(CachePolicy.DISABLED).build())
            }
        }
        until("it is asked about") { server.asked.size == 2 }
        runBlocking { delay(600.milliseconds) }
        assertEquals(2, server.asked.size)
    }

    @Test
    fun `a picture fetched ahead and drawn before it lands is downloaded once`() {
        server.answer = { Thread.sleep(200); it.send(200, png(RED)) }
        val loader = run(settle = 1.hours)
        loader.keep(PlatformContext.INSTANCE, server.url())
        until("the fetch is under way") { server.asked.size == 1 }
        assertEquals(RED, draw(loader).colour())
        assertEquals(1, server.asked.size)
    }

    @Test
    fun `a picture warmed at the size it is drawn at is drawn from memory`() {
        server.answer = { it.send(200, png(RED, edge = 256)) }
        val loader = run()
        loader.warm(PlatformContext.INSTANCE, server.url(), 64, 64)
        until("it is decoded") { loader.memoryCache?.keys.orEmpty().isNotEmpty() }
        // As a picture drawn in the interface asks: at its own size, filling it, whatever is at hand.
        fun at(edge: Int) = runBlocking {
            loader.execute(
                ImageRequest.Builder(PlatformContext.INSTANCE).data(server.url()).size(edge).scale(Scale.FILL).precision(Precision.INEXACT).build(),
            )
        }
        assertEquals(DataSource.MEMORY_CACHE, assertIs<SuccessResult>(at(64)).dataSource)
        assertEquals(DataSource.MEMORY_CACHE, assertIs<SuccessResult>(at(32)).dataSource, "a smaller one is drawn from it too")
        assertEquals(DataSource.DISK, assertIs<SuccessResult>(at(128)).dataSource, "a larger one is not stretched out of it")
    }

    @Test
    fun `a picture kept ahead is on disk and not in memory`() {
        val loader = run(settle = 1.hours)
        loader.keep(PlatformContext.INSTANCE, server.url())
        until("it is on disk") { loader.diskCache?.openSnapshot(server.url())?.also { it.close() } != null }
        assertEquals(emptySet(), loader.memoryCache?.keys.orEmpty())
        assertEquals(DataSource.DISK, assertIs<SuccessResult>(draw(loader)).dataSource)
        assertEquals(1, server.asked.size, "drawing it asks for nothing")
    }
}
