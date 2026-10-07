package hivens.launcher.catalogue

import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.cache.Cache
import hivens.core.cache.CacheValue
import hivens.core.cache.Freshness
import hivens.core.cache.PassthroughCache
import hivens.launcher.cache.SmrtPackCaches
import hivens.launcher.smrt.SmrtPackClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MirrorPackCatalogueTest {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    private fun pack(id: String) =
        """{"pack_id":"$id","display_name":"$id","tagline":"","minecraft_version":"1.21.1","latest_pack_version":"1"}"""

    private fun listing(vararg ids: String) =
        """{"schema_version":2,"generated_at":"t","packs":[${ids.joinToString(",") { pack(it) }}]}"""

    /** Answers the n-th request with the n-th body, the last one from then on. A null body is a 500. */
    private fun mirror(vararg bodies: String?, caches: SmrtPackCaches = SmrtPackCaches.passthrough()): Pair<SmrtPackClient, AtomicInteger> {
        val calls = AtomicInteger(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    val body = bodies[minOf(calls.getAndIncrement(), bodies.size - 1)]
                    if (body == null) {
                        respond(ByteReadChannel("down"), HttpStatusCode.InternalServerError)
                    } else {
                        respond(ByteReadChannel(body.toByteArray()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                }
            }
        }
        return SmrtPackClient(HttpClientProvider { client }, "https://mirror.test", caches = caches) to calls
    }

    private fun ids(answers: List<List<CataloguePack>>) = answers.map { a -> a.map { it.id } }

    @Test
    fun `a pack published while Browse is open arrives on the next poll`() = runBlocking {
        val (client, _) = mirror(listing("a"), listing("a", "b"))
        val answers = withTimeout(5_000) {
            MirrorPackCatalogue(client, pollIntervalMs = 10).searchStream("").take(2).toList()
        }
        assertEquals(listOf(listOf("a"), listOf("a", "b")), ids(answers))
    }

    @Test
    fun `an unchanged answer is not handed on again`() = runBlocking {
        val (client, calls) = mirror(listing("a"), listing("a"), listing("a"), listing("a", "b"))
        val answers = withTimeout(5_000) {
            MirrorPackCatalogue(client, pollIntervalMs = 10).searchStream("").take(2).toList()
        }
        assertEquals(listOf(listOf("a"), listOf("a", "b")), ids(answers))
        assertEquals(4, calls.get(), "the two identical polls were asked and swallowed")
    }

    @Test
    fun `a failed poll keeps the stream and the next one is heard`() = runBlocking {
        val (client, _) = mirror(listing("a"), null, listing("a", "b"))
        val answers = withTimeout(5_000) {
            MirrorPackCatalogue(client, pollIntervalMs = 10).searchStream("").take(2).toList()
        }
        assertEquals(listOf(listOf("a"), listOf("a", "b")), ids(answers))
    }

    private val translated =
        """{"schema_version":2,"generated_at":"t","packs":[""" +
            """{"pack_id":"t","display_name":"T","tagline":"Тяжёлая промышленность.","minecraft_version":"1.21.1",""" +
            """"latest_pack_version":"1","tagline_i18n":{"en":"Heavy industry."}},""" +
            """{"pack_id":"u","display_name":"U","tagline":"Без перевода","minecraft_version":"1.21.1","latest_pack_version":"1"}]}"""

    @Test
    fun `a card reads in the reader's language where the curator wrote it, and untagged where not`() = runBlocking {
        val (client, _) = mirror(translated)
        var tag = "en"
        val catalogue = MirrorPackCatalogue(client, language = { tag })
        assertEquals(listOf("Heavy industry.", "Без перевода"), catalogue.search("").map { it.tagline })
        tag = "de"
        assertEquals(listOf("Тяжёлая промышленность.", "Без перевода"), catalogue.search("").map { it.tagline })
    }

    @Test
    fun `a search matches the tagline the reader sees`() = runBlocking {
        val (client, _) = mirror(translated)
        val catalogue = MirrorPackCatalogue(client, language = { "en" })
        assertEquals(listOf("t"), catalogue.search("industry").map { it.id })
    }

    /** A listing cache holding [stored], whose stale-then-fresh view fails after handing it over. */
    private class FailingRefreshCache(private val stored: SmrtPackListing) : Cache<SmrtPackListing> {
        override suspend fun get(key: String, loader: suspend () -> SmrtPackListing) = loader()
        override suspend fun refresh(key: String, loader: suspend () -> SmrtPackListing) = loader()
        override fun flow(key: String, loader: suspend () -> SmrtPackListing): Flow<CacheValue<SmrtPackListing>> = flow {
            emit(CacheValue(stored, Freshness.STALE))
            throw IOException("mirror unreachable")
        }
        override suspend fun invalidate(key: String) {}
        override suspend fun invalidateAll() {}
    }

    @Test
    fun `a refresh that fails behind the stored list still leaves the poll running`() = runBlocking {
        val stored = lenientJson.decodeFromString(SmrtPackListing.serializer(), listing("a"))
        val caches = SmrtPackCaches(FailingRefreshCache(stored), PassthroughCache(), PassthroughCache(), PassthroughCache())
        val (client, _) = mirror(listing("a", "b"), caches = caches)
        val answers = withTimeout(5_000) {
            MirrorPackCatalogue(client, pollIntervalMs = 10).searchStream("").take(2).toList()
        }
        assertEquals(listOf(listOf("a"), listOf("a", "b")), ids(answers))
    }

    @Test
    fun `the mirror answers its whole listing, so it does not page`() {
        val (client, _) = mirror(listing("a"))
        assertFalse(MirrorPackCatalogue(client).paged)
    }
}
