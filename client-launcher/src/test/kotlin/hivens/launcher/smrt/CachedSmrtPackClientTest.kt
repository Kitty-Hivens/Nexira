package hivens.launcher.smrt

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.cache.Cache
import hivens.core.cache.CacheConfig
import hivens.core.cache.PassthroughCache
import hivens.launcher.cache.CacheFactory
import hivens.launcher.cache.SmrtPackCaches
import hivens.test.TestClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CachedSmrtPackClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mirror = "https://mirror.test"
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("cached-smrt-test-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val listingBody =
        """{"schema_version":2,"generated_at":"t","packs":[]}"""
    private val manifestBody =
        """{"schema_version":2,"pack_id":"p","pack_version":"1","generated_at":"t",""" +
            """"minecraft":{"version":"1.21.1"},"loader":{"name":"neoforge","version":"21"},"java":{"major":21}}"""

    /** A counting MockEngine that answers any GET with [body]. */
    private fun provider(counter: AtomicInteger, body: String): HttpClientProvider {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    counter.incrementAndGet()
                    respond(
                        content = ByteReadChannel(body.toByteArray()),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            }
        }
        return HttpClientProvider { client }
    }

    private fun TestScope.caches(
        clock: TestClock,
        listingConfig: CacheConfig<SmrtPackListing> = CacheConfig(ttlMs = 1_000, staleTtlMs = 100_000),
        manifestConfig: CacheConfig<SmrtPackManifest> = CacheConfig(ttlMs = 10_000_000, staleTtlMs = Long.MAX_VALUE),
    ): SmrtPackCaches {
        val f = CacheFactory(
            rootDir = dir.resolve("cache"),
            json = json,
            scope = this,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        return SmrtPackCaches(
            listing = f.create("pack-listing", SmrtPackListing.serializer(), listingConfig),
            summary = PassthroughCache(),
            manifest = f.create("pack-manifest", SmrtPackManifest.serializer(), manifestConfig),
        )
    }

    @Test
    fun `repeated listPacks hits the network once`() = runTest {
        val counter = AtomicInteger(0)
        val client = SmrtPackClient(provider(counter, listingBody), mirror, json, caches(TestClock()))

        client.listPacks()
        client.listPacks()
        client.listPacks()
        assertEquals(1, counter.get(), "warm cache must not re-hit the mirror")
    }

    @Test
    fun `stale listing serves cached value then revalidates`() = runTest {
        val counter = AtomicInteger(0)
        val clock = TestClock()
        val client = SmrtPackClient(provider(counter, listingBody), mirror, json, caches(clock))

        client.listPacks()                 // miss -> 1 network call
        assertEquals(1, counter.get())
        clock.advance(2_000)               // past the 1s TTL -> stale
        client.listPacks()                 // serves stale, triggers background refresh
        // The refresh is fire-and-forget through Ktor, whose call is not bound to
        // the virtual scheduler -- advanceUntilIdle() can return before the
        // background read lands (rare, timing-dependent; surfaced on a slower CI
        // runner). Await the observable effect within a real-time bound instead.
        advanceUntilIdle()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { while (counter.get() < 2) delay(10) }
        }
        assertEquals(2, counter.get(), "stale read revalidates exactly once in the background")
    }

    @Test
    fun `invalidate forces the next listPacks to refetch`() = runTest {
        val counter = AtomicInteger(0)
        val smrtCaches = caches(TestClock())
        val client = SmrtPackClient(provider(counter, listingBody), mirror, json, smrtCaches)

        client.listPacks()
        assertEquals(1, counter.get())
        (smrtCaches.listing as Cache<SmrtPackListing>).invalidate("$mirror/v1/packs")
        client.listPacks()
        assertEquals(2, counter.get(), "post-invalidate listPacks reloads")
    }

    @Test
    fun `a pinned manifest version is fetched once and stays cached`() = runTest {
        val counter = AtomicInteger(0)
        val clock = TestClock()
        val client = SmrtPackClient(provider(counter, manifestBody), mirror, json, caches(clock))

        client.fetchManifestVersion("p", "1")
        clock.advance(60_000)              // well within the long manifest TTL
        client.fetchManifestVersion("p", "1")
        assertEquals(1, counter.get(), "immutable pinned manifest stays a warm hit")
    }
}
