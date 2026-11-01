package hivens.launcher.modrinth

import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthFile
import hivens.core.api.dto.modrinth.ModrinthHashes
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ModrinthClient.newestMatchingVersion], which the pack's browser and the project
 * page install by: the build [chooseBuild] picks out of the project's listing.
 */
class ModrinthClientVersionChoiceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun build(
        id: String,
        published: String,
        loaders: List<String>,
        games: List<String> = listOf("1.21.1"),
        type: String = "release",
    ) = ModrinthVersion(
        id = id, projectId = "p", versionNumber = id, versionType = type, gameVersions = games, loaders = loaders,
        datePublished = published,
        files = listOf(ModrinthFile(ModrinthHashes("00"), "https://cdn.test/$id.jar", "$id.jar", primary = true, size = 1)),
    )

    private fun client(listing: List<ModrinthVersion>): ModrinthClient {
        val provider = HttpClientProvider {
            HttpClient(MockEngine { req ->
                if (!req.url.encodedPath.endsWith("/v2/project/p/version")) return@MockEngine respond(ByteReadChannel("no"), HttpStatusCode.NotFound)
                respond(
                    ByteReadChannel(json.encodeToString(ListSerializer(ModrinthVersion.serializer()), listing).toByteArray()),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"),
                )
            })
        }
        return ModrinthClient(provider, testTransferEngine(provider), json)
    }

    @Test
    fun `the newest fitting build by publish date is taken, whatever the listing order`() = runTest {
        val older = build("old", "2025-01-01T00:00:00Z", listOf("neoforge"))
        val newer = build("new", "2026-01-01T00:00:00Z", listOf("neoforge"))

        assertEquals("new", client(listOf(older, newer)).newestMatchingVersion("p", "1.21.1", "neoforge")?.id)
    }

    @Test
    fun `a release is taken over a newer beta, and a beta when there is no release`() = runTest {
        val release = build("release", "2025-01-01T00:00:00Z", listOf("neoforge"))
        val beta = build("beta", "2026-01-01T00:00:00Z", listOf("neoforge"), type = "beta")

        assertEquals("release", client(listOf(beta, release)).newestMatchingVersion("p", "1.21.1", "neoforge")?.id)
        assertEquals("beta", client(listOf(beta)).newestMatchingVersion("p", "1.21.1", "neoforge")?.id)
    }

    @Test
    fun `a loader that runs another loader's mods takes its builds`() = runTest {
        val fabric = build("fabric", "2026-01-01T00:00:00Z", listOf("fabric"))
        val forge = build("forge", "2026-01-01T00:00:00Z", listOf("forge"), games = listOf("1.20.1"))
        val forge1211 = build("forge", "2026-01-01T00:00:00Z", listOf("forge"))

        assertEquals("fabric", client(listOf(fabric)).newestMatchingVersion("p", "1.21.1", "quilt")?.id)
        assertEquals("forge", client(listOf(forge)).newestMatchingVersion("p", "1.20.1", "neoforge")?.id)
        assertNull(client(listOf(forge1211)).newestMatchingVersion("p", "1.21.1", "neoforge"), "NeoForge stopped reading Forge mods after 1.20.1")
        assertEquals("forge", client(listOf(forge1211)).newestMatchingVersion("p", "1.21.1", "cleanroom")?.id)
    }

    @Test
    fun `a build for another game version or loader is no answer`() = runTest {
        val other = build("other", "2026-01-01T00:00:00Z", listOf("neoforge"), games = listOf("1.20.1"))

        assertNull(client(listOf(other)).newestMatchingVersion("p", "1.21.1", "neoforge"))
    }

    @Test
    fun `a pack with no loader runs no mods, and still takes a resource pack`() = runTest {
        val mod = build("mod", "2026-01-01T00:00:00Z", listOf("fabric"))
        val pack = build("pack", "2026-01-01T00:00:00Z", listOf("minecraft"))

        assertNull(client(listOf(mod)).newestMatchingVersion("p", "1.21.1", ""))
        assertEquals("pack", client(listOf(pack)).newestMatchingVersion("p", "1.21.1", "")?.id)
    }

    @Test
    fun `a blank game version is not a constraint`() = runTest {
        val any = build("any", "2026-01-01T00:00:00Z", listOf("fabric"), games = listOf("1.20.1"))

        assertEquals("any", client(listOf(any)).newestMatchingVersion("p", "", "fabric")?.id)
    }
}
