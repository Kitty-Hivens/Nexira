package hivens.launcher.protocol

import hivens.config.Storage
import hivens.core.api.HttpClientProvider
import hivens.launcher.network.ServerProtocolConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * #189 -- LauncherHashCache.refreshAttempts was a plain Int. Two parallel
 * refresh() calls could both observe `< MAX`, both pass the cap, and both
 * download. AtomicInteger + CAS makes the cap exact under contention.
 */
class LauncherHashCacheTest {

    private lateinit var dataDir: java.io.File

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("aura-hash-cache-test-").toFile()
    }

    @AfterTest
    fun teardown() {
        dataDir.walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun `concurrent refreshes obey MAX_REFRESH_ATTEMPTS_PER_SESSION`() = runBlocking {
        val downloads = AtomicInteger(0)
        val cache = LauncherHashCache(
            dataDir = dataDir,
            clientProvider = countingProvider(downloads, body = "fake-jar-bytes".toByteArray()),
            config = ServerProtocolConfig(),
        )

        val parallel = 64
        val results = coroutineScope {
            (1..parallel).map { async(Dispatchers.IO) { cache.refresh() } }.awaitAll()
        }

        // Exactly MAX successful refreshes; the rest must short-circuit to null.
        val successful = results.count { it != null }
        val rejected = results.count { it == null }
        assertEquals(LauncherHashCache.MAX_REFRESH_ATTEMPTS_PER_SESSION, successful,
            "only MAX refreshes may consume a slot -- got $successful")
        assertEquals(parallel - LauncherHashCache.MAX_REFRESH_ATTEMPTS_PER_SESSION, rejected)
        assertEquals(LauncherHashCache.MAX_REFRESH_ATTEMPTS_PER_SESSION, downloads.get(),
            "download count must match successful refresh count -- got ${downloads.get()}")
    }

    @Test
    fun `successful refresh updates get() and persists to cache file`() = runBlocking {
        val cache = LauncherHashCache(
            dataDir = dataDir,
            clientProvider = countingProvider(AtomicInteger(0), body = "fake-jar-bytes".toByteArray()),
            config = ServerProtocolConfig(),
        )

        val newHash = cache.refresh()
        assertNotNull(newHash)
        assertEquals(newHash, cache.get())
        assertEquals(newHash, dataDir.resolve(Storage.HASH_CACHE_FILE).readText().trim())
    }

    @Test
    fun `empty download body returns null without consuming a refresh slot`() = runBlocking {
        // Edge case: server returned 200 OK but with zero bytes (CDN glitch,
        // truncated response). Refresh must signal failure so the caller can
        // surface "client too old" rather than persist an MD5 of empty bytes.
        val downloads = AtomicInteger(0)
        val cache = LauncherHashCache(
            dataDir = dataDir,
            clientProvider = countingProvider(downloads, body = ByteArray(0)),
            config = ServerProtocolConfig(),
        )

        assertNull(cache.refresh())
        assertEquals(1, downloads.get(), "the network attempt did happen -- slot is consumed")
    }

    private fun countingProvider(counter: AtomicInteger, body: ByteArray): HttpClientProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    counter.incrementAndGet()
                    respond(
                        content = ByteReadChannel(body),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/octet-stream"),
                    )
                }
            }
        }
        return HttpClientProvider { client }
    }
}
