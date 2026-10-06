package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiteLoaderResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** The shape of the live index, trimmed to two versions, its md5 deliberately not the file's. */
    private val index = """
        {"meta":{},"versions":{
          "1.12.2":{"repo":{"stream":"SNAPSHOT","type":"m2","url":"http://repo.test/snapshots/","classifier":""},
            "snapshots":{"com.mumfrey:liteloader":{
              "latest":{"version":"1.12.2-SNAPSHOT","file":"liteloader-1.12.2-SNAPSHOT.jar","md5":"1420785ecbfed5aff4a586c5c9dd97eb",
                "tweakClass":"com.mumfrey.liteloader.launch.LiteLoaderTweaker",
                "libraries":[{"name":"net.minecraft:launchwrapper:1.12"},{"name":"org.ow2.asm:asm-all:5.2"}]}}}},
          "1.7.10":{"repo":{"stream":"RELEASE","type":"ivy","url":"http://dl.test/versions/","classifier":"mcpnames"},
            "artefacts":{"com.mumfrey:liteloader":{
              "latest":{"version":"1.7.10","file":"liteloader-1.7.10.jar","tweakClass":"com.mumfrey.liteloader.launch.LiteLoaderTweaker",
                "libraries":[{"name":"net.minecraft:launchwrapper:1.11"}]}}}}
        }}
    """.trimIndent()

    private val metadata = "<metadata><versioning><snapshot><timestamp>20171128.144431</timestamp><buildNumber>4</buildNumber></snapshot></versioning></metadata>"

    private fun engine(requested: MutableList<String>, online: () -> Boolean = { true }) = MockEngine { req ->
        val url = req.url.toString()
        requested += url
        if (!online()) return@MockEngine respond("offline", HttpStatusCode.ServiceUnavailable)
        when {
            url == "https://index.test/versions.json" -> respond(index, HttpStatusCode.OK)
            url.endsWith("/1.12.2-SNAPSHOT/maven-metadata.xml") -> respond(metadata, HttpStatusCode.OK)
            url.endsWith("liteloader-1.12.2-20171128.144431-4.jar.sha1") -> respond("e3197d8d8a4f60576bf9af88b654d2e94b8c56ea  liteloader.jar", HttpStatusCode.OK)
            url.endsWith(".sha1") && "launchwrapper" in url -> respond("1111111111111111111111111111111111111111", HttpStatusCode.OK)
            else -> respond("nope", HttpStatusCode.NotFound)
        }
    }

    private fun resolver(requested: MutableList<String>, cacheDir: java.nio.file.Path? = null, online: () -> Boolean = { true }) =
        LiteLoaderResolver(HttpClientProvider { HttpClient(engine(requested, online)) }, json, cacheDir, "https://index.test/versions.json")

    @Test
    fun `a snapshot is fetched over https under its timestamped name and held to the published sha1`() = runTest {
        val requested = mutableListOf<String>()
        val profile = resolver(requested).resolve("1.12.2", "")

        assertEquals("1.12.2-SNAPSHOT", profile.version)
        assertEquals(LiteLoaderResolver.LAUNCHWRAPPER_MAIN, profile.mainClass)
        assertEquals(listOf("--tweakClass", "com.mumfrey.liteloader.launch.LiteLoaderTweaker"), profile.gameArgs)
        val jar = profile.libraries.first { it.coord.groupArtifact == "com.mumfrey:liteloader" }
        assertEquals("https://repo.test/snapshots/com/mumfrey/liteloader/1.12.2-SNAPSHOT/liteloader-1.12.2-20171128.144431-4.jar", jar.url)
        assertEquals("e3197d8d8a4f60576bf9af88b654d2e94b8c56ea", jar.sha1)
        assertTrue(requested.none { it.startsWith("http://") }, "every fetch is made over https: $requested")
    }

    @Test
    fun `its libraries come from where they are published`() = runTest {
        val profile = resolver(mutableListOf()).resolve("1.12.2", "")
        val wrapper = profile.libraries.first { it.coord.groupArtifact == "net.minecraft:launchwrapper" }
        assertEquals("https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar", wrapper.url)
        assertEquals("1111111111111111111111111111111111111111", wrapper.sha1)
        val asm = profile.libraries.first { it.coord.groupArtifact == "org.ow2.asm:asm-all" }
        assertEquals("https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.2/asm-all-5.2.jar", asm.url)
        assertNull(asm.sha1, "no sha1 published: fetched on https alone")
    }

    @Test
    fun `a released build in an ivy repository is taken by its own file name`() = runTest {
        val profile = resolver(mutableListOf()).resolve("1.7.10", "")
        assertEquals(
            "https://dl.test/versions/com/mumfrey/liteloader/1.7.10/liteloader-1.7.10.jar",
            profile.libraries.first { it.coord.groupArtifact == "com.mumfrey:liteloader" }.url,
        )
    }

    @Test
    fun `a version LiteLoader never served is refused by name`() = runTest {
        val e = assertFailsWith<IOException> { resolver(mutableListOf()).resolve("1.16.5", "") }
        assertTrue("1.16.5" in e.message.orEmpty())
        assertFailsWith<IOException> { resolver(mutableListOf()).resolve("1.12.2", "1.12.2-RELEASE") }
    }

    @Test
    fun `a named build relaunches from the kept profile with the network gone`() = runTest {
        val cacheDir = Files.createTempDirectory("liteloader-cache")
        try {
            var online = true
            val r = resolver(mutableListOf(), cacheDir) { online }
            r.resolve("1.12.2", "")
            online = false
            val again = r.resolve("1.12.2", "1.12.2-SNAPSHOT")
            assertEquals("1.12.2-SNAPSHOT", again.version)
            assertEquals(3, again.libraries.size)
        } finally {
            cacheDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the versions on offer are the index's for that game version`() = runTest {
        val options = resolver(mutableListOf()).availableVersions("1.12.2")
        assertEquals(listOf(LoaderVersionOption("1.12.2-SNAPSHOT", stable = false)), options)
    }
}
