package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
