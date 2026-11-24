package hivens.core.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * A host that stops answering, over a real connection: a mock engine has no socket to fall silent.
 * The client carries the bounds the launcher's does, scaled down, so what is measured is that the
 * engine does not live by them.
 */
class TransferStallTest {

    private val dir: Path = Files.createTempDirectory("xfer-stall")
    private val done = CountDownLatch(1)
    private val asked = AtomicInteger()
    private val body = ByteArray(1_000) { (it % 251).toByte() }

    @Volatile
    private var answer: (HttpExchange, Int) -> Unit = { _, _ -> }

    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange -> exchange.use { val _ = runCatching { answer(it, asked.incrementAndGet()) } } }
        executor = pool
        start()
    }
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 1_500
            socketTimeoutMillis = 60_000
        }
    }

    @AfterTest
    fun tearDown() {
        done.countDown()
        client.close()
        server.stop(0)
        pool.shutdownNow()
        dir.toFile().deleteRecursively()
    }

    private fun engine() = TransferEngine(
        http = HttpClientProvider { client },
        gate = AdaptiveGate(initial = 2, min = 1, max = 2),
        backoffMs = listOf(0L, 0L, 0L),
        stallMs = 400L,
    )

    private fun transfer() = Transfer("http://127.0.0.1:${server.address.port}/mod.jar", dir.resolve("mod.jar"), size = body.size.toLong())

    private fun HttpExchange.start() = sendResponseHeaders(200, body.size.toLong())

    @Test
    fun `a host that falls silent half way is asked again`() = runBlocking {
        answer = { exchange, n ->
            exchange.start()
            if (n == 1) {
                exchange.responseBody.write(body, 0, 500)
                exchange.responseBody.flush()
                done.await(20, TimeUnit.SECONDS)
            } else {
                exchange.responseBody.write(body)
            }
        }
        val _ = engine().fetch(transfer())
        assertContentEquals(body, Files.readAllBytes(dir.resolve("mod.jar")))
        assertEquals(2, asked.get())
    }

    @Test
    fun `a host that never answers fails in the time its attempts take and no more`() {
        answer = { _, _ -> done.await(20, TimeUnit.SECONDS) }
        val from = System.nanoTime()
        assertFails { runBlocking { engine().fetch(transfer()) } }
        val took = (System.nanoTime() - from) / 1_000_000
        assertEquals(3, asked.get(), "every attempt was spent")
        assertTrue(took < 10_000, "took $took ms")
    }

    @Test
    fun `a slow host that keeps sending is left to finish, however long it takes`() = runBlocking {
        answer = { exchange, _ ->
            exchange.start()
            for (part in 0 until 10) {
                exchange.responseBody.write(body, part * 100, 100)
                exchange.responseBody.flush()
                Thread.sleep(200)
            }
        }
        val from = System.nanoTime()
        val _ = engine().fetch(transfer())
        assertTrue((System.nanoTime() - from) / 1_000_000 > 1_500, "longer than the client lets a request run")
        assertContentEquals(body, Files.readAllBytes(dir.resolve("mod.jar")))
        assertEquals(1, asked.get())
    }
}
