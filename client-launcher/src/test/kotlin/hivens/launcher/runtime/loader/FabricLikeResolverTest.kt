package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import hivens.launcher.runtime.MavenCoord
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FabricLikeResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `resolve maps profile libs to base-plus-path urls and keeps mainClass`() = runTest {
        val profileJson = """
            {"mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
             "libraries":[
               {"name":"org.ow2.asm:asm:9.9","url":"https://maven.fabricmc.net/","sha1":"abc","size":126122},
               {"name":"net.fabricmc:fabric-loader:0.16.0","url":"https://maven.fabricmc.net/"}
             ]}
        """.trimIndent()
        val profileUrl = "https://meta.test/v2/versions/loader/1.20.1/0.16.0/profile/json"
        val engine = MockEngine { req ->
            if (req.url.toString() == profileUrl) respond(profileJson, HttpStatusCode.OK)
            else respond("nope", HttpStatusCode.NotFound)
        }
        val resolver = FabricLikeResolver(HttpClientProvider { HttpClient(engine) }, json, "fabric", "https://meta.test/v2")

        val profile = resolver.resolve("1.20.1", "0.16.0")

        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", profile.mainClass)
        assertEquals(2, profile.libraries.size)

        val asm = profile.libraries.first { it.coord.groupArtifact == "org.ow2.asm:asm" }
        assertEquals("https://maven.fabricmc.net/org/ow2/asm/asm/9.9/asm-9.9.jar", asm.url)
        assertEquals("abc", asm.sha1)

        val loader = profile.libraries.first { it.coord.groupArtifact == "net.fabricmc:fabric-loader" }
        assertEquals("https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.16.0/fabric-loader-0.16.0.jar", loader.url)
        assertNull(loader.sha1, "an omitted sha1 -> null (download without verification)")
    }

    @Test
    fun `blank loader version resolves the latest stable, never an empty url segment`() = runTest {
        // Meta list is newest-first; a newer non-stable precedes the newest stable.
        val listJson = """
            [ {"loader":{"version":"0.20.0","stable":false}},
              {"loader":{"version":"0.19.3","stable":true}},
              {"loader":{"version":"0.19.0","stable":true}} ]
        """.trimIndent()
        val profileJson = """{"mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient","libraries":[]}"""
        val listUrl    = "https://meta.test/v2/versions/loader/1.21.1"
        val profileUrl = "https://meta.test/v2/versions/loader/1.21.1/0.19.3/profile/json"
        val requested = mutableListOf<String>()
        val engine = MockEngine { req ->
            requested += req.url.toString()
            when (req.url.toString()) {
                listUrl    -> respond(listJson, HttpStatusCode.OK)
                profileUrl -> respond(profileJson, HttpStatusCode.OK)
                else       -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val resolver = FabricLikeResolver(HttpClientProvider { HttpClient(engine) }, json, "fabric", "https://meta.test/v2")

        val profile = resolver.resolve("1.21.1", "") // blank -> resolver default

        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", profile.mainClass)
        assertTrue(requested.none { it.contains("//profile/json") }, "must not build the empty-segment URL: $requested")
        assertTrue(profileUrl in requested, "should fetch the newest STABLE loader's profile (0.19.3), not the newer 0.20.0")
    }

    /**
     * The profile for a named version never changes upstream, and fetching it on
     * every launch was the one thing that kept a provisioned pack from starting
     * offline.
     */
    @Test
    fun `a named version relaunches from the kept profile with the network gone`() = runTest {
        val cacheDir = Files.createTempDirectory("fabric-cache").also { it.toFile().deleteOnExit() }
        val profileJson = """{"mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient","libraries":[]}"""
        var online = true
        val engine = MockEngine { req ->
            if (online && req.url.toString().endsWith("/1.20.1/0.16.0/profile/json")) respond(profileJson, HttpStatusCode.OK)
            else respond("offline", HttpStatusCode.ServiceUnavailable)
        }
        val resolver = FabricLikeResolver(HttpClientProvider { HttpClient(engine) }, json, "fabric", "https://meta.test/v2", cacheDir)
        resolver.resolve("1.20.1", "0.16.0")

        online = false
        val again = resolver.resolve("1.20.1", "0.16.0")

        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", again.mainClass)
        assertEquals("0.16.0", again.version)
        cacheDir.toFile().deleteRecursively()
    }

    /**
     * Legacy Fabric ships its own LWJGL 2, and the natives as an old-style
     * natives-only entry. Taken as a plain library it was a 404 on the classpath,
     * and vanilla's natives ran under the loader's Java half.
     */
    @Test
    fun `a natives-only entry becomes the loader's natives in place of vanilla's`() = runTest {
        val profileJson = """
            {"mainClass":"net.fabricmc.loader.impl.launch.knot.KnotClient",
             "libraries":[
               {"name":"org.lwjgl.lwjgl:lwjgl:2.9.4+legacyfabric.17","url":"https://maven.legacyfabric.test/"},
               {"name":"org.lwjgl.lwjgl:lwjgl-platform:2.9.4+legacyfabric.17","url":"https://maven.legacyfabric.test/",
                "natives":{"linux":"natives-linux","osx":"natives-osx","windows":"natives-windows","old":"natives-windows-${'$'}{arch}"}}
             ]}
        """.trimIndent()
        val engine = MockEngine { req ->
            if (req.url.toString().endsWith("/1.8.9/0.19.5/profile/json")) respond(profileJson, HttpStatusCode.OK)
            else respond("nope", HttpStatusCode.NotFound)
        }
        val resolver = FabricLikeResolver(HttpClientProvider { HttpClient(engine) }, json, "legacy-fabric", "https://meta.test/v2")

        val profile = resolver.resolve("1.8.9", "0.19.5")

        assertEquals(listOf("org.lwjgl.lwjgl:lwjgl"), profile.libraries.map { it.coord.groupArtifact }, "the natives entry has no plain jar to put on -cp")
        val natives = profile.nativesOverride.orEmpty()
        assertEquals(setOf("natives-linux", "natives-osx", "natives-windows"), natives.mapNotNull { it.coord.classifier }.toSet())
        assertEquals(
            "https://maven.legacyfabric.test/org/lwjgl/lwjgl/lwjgl-platform/2.9.4+legacyfabric.17/lwjgl-platform-2.9.4+legacyfabric.17-natives-linux.jar",
            natives.first { it.coord.classifier == "natives-linux" }.url,
        )
        val vanillaPlatform = MavenCoord.parse("org.lwjgl.lwjgl:lwjgl-platform:2.9.4-nightly-20150209:natives-linux")
        assertTrue(profile.removeFromBase(vanillaPlatform), "vanilla's natives for the same artifact go")
        assertFalse(profile.removeFromBase(MavenCoord.parse("net.java.jinput:jinput-platform:2.0.5:natives-linux")))
    }

    @Test
    fun `a plain Fabric profile swaps nothing out of vanilla`() = runTest {
        val profileJson = """{"mainClass":"m","libraries":[{"name":"net.fabricmc:fabric-loader:0.16.0","url":"https://maven.fabricmc.net/"}]}"""
        val engine = MockEngine { respond(profileJson, HttpStatusCode.OK) }
        val profile = FabricLikeResolver(HttpClientProvider { HttpClient(engine) }, json, "fabric", "https://meta.test/v2").resolve("1.20.1", "0.16.0")
        assertNull(profile.nativesOverride)
        assertFalse(profile.removeFromBase(MavenCoord.parse("org.lwjgl:lwjgl:3.3.1")))
    }
}
