package hivens.launcher.catalogue

import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.dto.smrt.SmrtManifestVersions
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.cache.Cache
import hivens.core.cache.CacheValue
import hivens.core.cache.Freshness
import hivens.core.cache.PassthroughCache
import hivens.core.data.PackAuthRequirement
import hivens.launcher.cache.SmrtPackCaches
import hivens.launcher.smrt.SmrtPackClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    /**
     * Answers the n-th listing request with the n-th body, the last one from then on.
     * A null body is a 500. The community listing answers [community] apart from
     * them, and a null there is a 500 too.
     */
    private fun mirror(
        vararg bodies: String?,
        caches: SmrtPackCaches = SmrtPackCaches.passthrough(),
        community: String? = "[]",
    ): Pair<SmrtPackClient, AtomicInteger> {
        val calls = AtomicInteger(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    if (req.url.encodedPath == "/v1/community") {
                        return@addHandler if (community == null) {
                            respond(ByteReadChannel("down"), HttpStatusCode.InternalServerError)
                        } else {
                            respond(ByteReadChannel(community.toByteArray()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                        }
                    }
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

    private fun member(id: String, owner: String) = """{"summary":${pack(id)},"owner_login":"$owner"}"""

    @Test
    fun `the community's packs follow the mirror's own, each with its owner`() = runBlocking {
        val (client, _) = mirror(listing("a", "b"), community = "[${member("u/7/cozy", "alex")},${member("b", "sam")}]")
        val packs = MirrorPackCatalogue(client).search("")
        assertEquals(listOf("a", "b", "u/7/cozy"), packs.map { it.id }, "a pack in both listings is the mirror's own")
        assertEquals(listOf(null, null, "alex"), packs.map { it.author })
        assertEquals(listOf(false, false, true), packs.map { it.community })
    }

    @Test
    fun `a community listing that fails leaves the mirror's own packs`() = runBlocking {
        val (client, _) = mirror(listing("a"), community = null)
        assertEquals(listOf("a"), MirrorPackCatalogue(client).search("").map { it.id })
        val answers = withTimeout(5_000) {
            MirrorPackCatalogue(client, pollIntervalMs = 10).searchStream("").take(1).toList()
        }
        assertEquals(listOf(listOf("a")), ids(answers))
    }

    @Test
    fun `the community's packs join the stream`() = runBlocking {
        val (client, _) = mirror(listing("a"), community = "[${member("u/7/cozy", "alex")}]")
        val joined = withTimeout(5_000) {
            MirrorPackCatalogue(client, pollIntervalMs = 10_000).searchStream("").first { list -> list.any { it.community } }
        }
        assertEquals(listOf("a", "u/7/cozy"), joined.map { it.id })
    }

    @Test
    fun `the mirror answers its whole listing, so it does not page`() {
        val (client, _) = mirror(listing("a"))
        assertFalse(MirrorPackCatalogue(client).paged)
    }

    /** A cache holding [stored] past its age: a single read hands it back, a stream hands it and then the load. */
    private class StoredThenLoaded<V>(private val stored: V) : Cache<V> {
        override suspend fun get(key: String, loader: suspend () -> V) = stored
        override suspend fun refresh(key: String, loader: suspend () -> V) = loader()
        override fun flow(key: String, loader: suspend () -> V): Flow<CacheValue<V>> = flow {
            emit(CacheValue(stored, Freshness.STALE))
            emit(CacheValue(loader(), Freshness.FRESH))
        }
        override suspend fun invalidate(key: String) {}
        override suspend fun invalidateAll() {}
    }

    private fun summaryOf(version: String) =
        """{"pack_id":"P","display_name":"P","tagline":"t","minecraft_version":"1.21.1","latest_pack_version":"$version"}"""

    private fun manifestOf(version: String, java: Int) =
        """{"schema_version":2,"pack_id":"P","pack_version":"$version","generated_at":"t","minecraft":{"version":"1.21.1"},
            "loader":{"name":"fabric","version":"0.16.0"},"java":{"major":$java}}"""

    private fun buildsOf(version: String) = """{"latest":"$version","builds":[{"version_number":"$version","version_type":"release"}]}"""

    /** Caches holding a pack page read when 0.3.0 was current. */
    private fun storedAt030() = SmrtPackCaches(
        listing = PassthroughCache(),
        summary = StoredThenLoaded(lenientJson.decodeFromString(SmrtPackSummary.serializer(), summaryOf("0.3.0"))),
        manifest = StoredThenLoaded(lenientJson.decodeFromString(SmrtPackManifest.serializer(), manifestOf("0.3.0", 17))),
        versions = StoredThenLoaded(lenientJson.decodeFromString(SmrtManifestVersions.serializer(), buildsOf("0.3.0"))),
    )

    /**
     * Read once, the page showed the stored copy for as long as it stood, and its
     * Install took the build that was newest when the copy was made.
     */
    @Test
    fun `a pack page shows the stored read and then the mirror's answer`() = runBlocking {
        val client = routed(
            "/v1/packs/P" to summaryOf("0.3.1"),
            "/v1/packs/P/manifest" to manifestOf("0.3.1", 21),
            "/v1/packs/P/manifest/versions" to buildsOf("0.3.1"),
            caches = storedAt030(),
        )
        val pages = withTimeout(5_000) { MirrorPackCatalogue(client).detailsStream("P").toList() }

        assertEquals("0.3.0", pages.first().latestVersionId)
        assertEquals("0.3.1", pages.last().latestVersionId)
        assertEquals(listOf("0.3.1"), pages.last().versions.map { it.id })
        assertEquals("Java 21", pages.last().runtimeLabel)
    }

    @Test
    fun `a pack page keeps the stored read when the mirror cannot be asked`() = runBlocking {
        val client = routed(caches = storedAt030())
        val pages = withTimeout(5_000) { MirrorPackCatalogue(client).detailsStream("P").toList() }

        assertEquals(listOf("0.3.0"), pages.map { it.latestVersionId })
        assertEquals("Java 17", pages.single().runtimeLabel)
    }

    /** Answers by path, for a read that asks several endpoints at once. */
    private fun routed(vararg routes: Pair<String, String>, caches: SmrtPackCaches = SmrtPackCaches.passthrough()): SmrtPackClient {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = routes.firstOrNull { req.url.encodedPath == it.first }?.second
                    if (body == null) {
                        respond(ByteReadChannel("missing"), HttpStatusCode.NotFound)
                    } else {
                        respond(ByteReadChannel(body.toByteArray()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    }
                }
            }
        }
        return SmrtPackClient(HttpClientProvider { client }, "https://mirror.test", caches = caches)
    }

    @Test
    fun `a pack page carries the runtime, the sign-in and the pointer the mirror names`() = runBlocking {
        val client = routed(
            "/v1/packs/Industrial" to """{"pack_id":"Industrial","display_name":"Industrial","tagline":"t",
                "minecraft_version":"1.12.2","latest_pack_version":"0.3.1","tags":["tech"],
                "latest_built_at":"2026-10-01T10:00:00Z"}""",
            "/v1/packs/Industrial/manifest" to """{"schema_version":2,"pack_id":"Industrial","pack_version":"0.3.1",
                "generated_at":"2026-10-01T10:00:00Z","minecraft":{"version":"1.12.2"},
                "loader":{"name":"cleanroom","version":"0.3.1"},"java":{"major":21},
                "auth":{"kind":"smartycraft","server_id":"Industrial"}}""",
            "/v1/packs/Industrial/manifest/versions" to """{"latest":"0.3.1","builds":[
                {"version_number":"0.4.0","version_type":"beta","minecraft_version":"1.12.2",
                 "loader":{"name":"cleanroom","version":"0.3.2"},"mods_count":180,"size_bytes":900},
                {"version_number":"0.3.1","version_type":"release","minecraft_version":"1.12.2",
                 "loader":{"name":"forge","version":"14.23.5"},"mods_count":175}]}""",
        )
        val d = MirrorPackCatalogue(client).details("Industrial")
        assertEquals("0.3.1", d.latestVersionId, "the mirror's pointer, not the newest build")
        assertEquals(listOf("cleanroom", "forge"), d.loaders, "each build says what it ran on")
        assertEquals(listOf("1.12.2"), d.gameVersions)
        assertEquals("Java 21", d.runtimeLabel)
        assertEquals(PackAuthRequirement.SmartyCraft("Industrial"), d.auth)
        assertEquals("2026-10-01T10:00:00Z", d.updatedAt)
        assertEquals(listOf(180, 175), d.versions.map { it.modsCount })
        assertEquals(900L, d.versions.first().sizeBytes)
        assertEquals(emptyList(), d.creators, "the mirror's own pack credits nobody")
    }

    @Test
    fun `a community pack is asked for under its id as one path segment and names its owner`() = runBlocking {
        val client = routed(
            "/v1/packs/u%2F7%2Fcozy" to """{"pack_id":"u/7/cozy","display_name":"Cozy","tagline":"t",
                "minecraft_version":"1.21.1","latest_pack_version":"1","tier":"community","owner":7}""",
            "/v1/packs/u%2F7%2Fcozy/manifest" to """{"schema_version":2,"pack_id":"u/7/cozy","pack_version":"1",
                "generated_at":"2026-10-01T10:00:00Z","minecraft":{"version":"1.21.1"},
                "loader":{"name":"fabric","version":"0.16.0"},"java":{"major":21}}""",
            "/v1/community" to """[{"summary":{"pack_id":"u/7/cozy","display_name":"Cozy","tagline":"t",
                "minecraft_version":"1.21.1","latest_pack_version":"1","tier":"community"},"owner_login":"alex"}]""",
        )
        val d = MirrorPackCatalogue(client).details("u/7/cozy")
        assertEquals("Cozy", d.title)
        assertEquals(listOf("alex"), d.creators.map { it.name })
        assertEquals(null, d.creators.single().avatarUrl, "no picture is asked of anyone else for it")
    }
}
