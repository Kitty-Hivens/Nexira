package hivens.media

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoCacheServiceTest {

    private fun provider(counter: AtomicInteger, body: String, delayMs: Long = 0L): HttpClientProvider =
        HttpClientProvider {
            HttpClient(MockEngine {
                counter.incrementAndGet()
                if (delayMs > 0L) delay(delayMs)
                respond(ByteReadChannel(body), HttpStatusCode.OK)
            })
        }

    private fun cachedFiles(dir: Path): List<Path> =
        Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".part") }.toList() }

    @Test
    fun downloadsThenReusesWithoutRefetching() = runTest {
        val dir = Files.createTempDirectory("videocache")
        val scope = CoroutineScope(Dispatchers.IO)
        val counter = AtomicInteger(0)
        try {
            val svc = VideoCacheService(dir, provider(counter, "VIDEOBYTES"), scope)
            val first = svc.resolve("https://cdn.example.com/clip.mp4")
            assertTrue(Files.isRegularFile(first))
            assertEquals("VIDEOBYTES", Files.readString(first))
            assertEquals(1, counter.get())

            val second = svc.resolve("https://cdn.example.com/clip.mp4")
            assertEquals(first, second)
            assertEquals(1, counter.get(), "a cache hit must not refetch")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun concurrentResolvesCollapseToOneDownload() = runTest {
        val dir = Files.createTempDirectory("videocache")
        val scope = CoroutineScope(Dispatchers.IO)
        val counter = AtomicInteger(0)
        try {
            // The handler holds the response so both resolves are in flight together.
            val svc = VideoCacheService(dir, provider(counter, "X", delayMs = 150L), scope)
            val url = "https://cdn.example.com/same.mp4"
            val both = listOf(async { svc.resolve(url) }, async { svc.resolve(url) }).awaitAll()
            assertEquals(both[0], both[1])
            assertEquals(1, counter.get(), "single-flight must collapse concurrent fetches")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun evictsPastTheSizeCap() = runTest {
        val dir = Files.createTempDirectory("videocache")
        val scope = CoroutineScope(Dispatchers.IO)
        val counter = AtomicInteger(0)
        try {
            // Cap below two payloads, so caching the second evicts down to one file.
            val svc = VideoCacheService(dir, provider(counter, "AAAAAA"), scope, maxBytes = 10L)
            svc.resolve("https://cdn.example.com/a.mp4")
            svc.resolve("https://cdn.example.com/b.mp4")
            val files = cachedFiles(dir)
            assertEquals(1, files.size, "one file should be evicted under the cap")
            assertTrue(files.sumOf { Files.size(it) } <= 10L)
        } finally {
            scope.cancel()
        }
    }
}
