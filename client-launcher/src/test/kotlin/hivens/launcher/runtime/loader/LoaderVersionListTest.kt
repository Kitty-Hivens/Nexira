package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the create dialog's loader version picker is offered, per loader. */
class LoaderVersionListTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun serving(routes: Map<String, String>) = HttpClientProvider {
        HttpClient(MockEngine { req ->
            routes[req.url.toString()]?.let { respond(it, HttpStatusCode.OK) } ?: respond("missing", HttpStatusCode.NotFound)
        })
    }

    @Test
    fun `Fabric lists its loaders newest first and says which are stable`() = runTest {
        val provider = serving(mapOf(
            "https://meta.test/v2/versions/loader/1.21.1" to
                """[{"loader":{"version":"0.20.0","stable":false}},{"loader":{"version":"0.19.3","stable":true}}]""",
        ))

        val options = FabricLikeResolver(provider, json, "fabric", "https://meta.test/v2").availableVersions("1.21.1")

        assertEquals(listOf(LoaderVersionOption("0.20.0", stable = false), LoaderVersionOption("0.19.3")), options)
    }

    /** Quilt flags nothing stable, which would read as every build a beta. */
    @Test
    fun `a meta that flags nothing stable does not mark everything a pre-release`() = runTest {
        val provider = serving(mapOf(
            "https://meta.test/v3/versions/loader/1.21.1" to """[{"loader":{"version":"0.29.0"}},{"loader":{"version":"0.28.1"}}]""",
        ))

        val options = FabricLikeResolver(provider, json, "quilt", "https://meta.test/v3").availableVersions("1.21.1")

        assertTrue(options.all { it.stable }, "$options")
    }

    @Test
    fun `Forge lists every build for the version newest first with the recommended one marked`() = runTest {
        val provider = serving(mapOf(
            "https://forge.test/net/minecraftforge/forge/maven-metadata.xml" to
                "<metadata><versioning><versions><version>1.12.2-14.23.5.2859</version>" +
                "<version>1.12.2-14.23.5.2864</version><version>1.20.1-47.4.10</version></versions></versioning></metadata>",
            ModernInstallerResolver.FORGE_PROMOTIONS to """{"promos":{"1.12.2-recommended":"14.23.5.2859","1.12.2-latest":"14.23.5.2864"}}""",
        ))

        val options = ForgeLegacyResolver(provider, testTransferEngine(provider), json, forgeMavenBase = "https://forge.test").availableVersions("1.12.2")

        assertEquals(listOf("14.23.5.2864", "14.23.5.2859"), options.map { it.version })
        assertEquals(listOf(false, true), options.map { it.recommended })
    }

    @Test
    fun `NeoForge lists its line newest first, betas marked, snapshots left out`() = runTest {
        val provider = serving(mapOf(
            "${ModernInstallerResolver.NEOFORGE_META_VERSIONS}/neoforge" to
                """{"versions":["21.1.250","26.3.0.0-alpha.1+snapshot-1","26.3.0.15-beta","26.3.0.16-beta","26.2.0.88"]}""",
        ))
        val java = object : IJavaManager {
            override suspend fun getJavaPath(version: String): Path = Path.of("/bin/java")
            override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = Path.of("/bin/java")
        }
        val cache = Files.createTempDirectory("neo-list")

        val options = ModernInstallerResolver.neoforge(provider, testTransferEngine(provider), json, java, cache).availableVersions("26.3")

        assertEquals(listOf(LoaderVersionOption("26.3.0.16-beta", stable = false), LoaderVersionOption("26.3.0.15-beta", stable = false)), options)
        cache.toFile().deleteRecursively()
    }

    /** Cleanroom tags every build `-alpha` without flagging it pre-release. */
    @Test
    fun `a release listing keeps releases that carry the asset and marks alphas`() = runTest {
        val provider = serving(mapOf(
            "https://api.test/releases" to """[
                {"tag_name":"0.6.13-alpha","prerelease":false,"assets":[{"name":"cleanroom-0.6.13-alpha-installer.jar"}]},
                {"tag_name":"0.6.12","prerelease":false,"assets":[{"name":"cleanroom-0.6.12-installer.jar"}]},
                {"tag_name":"0.6.11","prerelease":false,"assets":[{"name":"sources.jar"}]}
            ]""",
        ))

        val options = githubReleaseVersions(provider, json, "https://api.test/releases") { "cleanroom-$it-installer.jar" }

        assertEquals(listOf(LoaderVersionOption("0.6.13-alpha", stable = false), LoaderVersionOption("0.6.12")), options)
    }
}
