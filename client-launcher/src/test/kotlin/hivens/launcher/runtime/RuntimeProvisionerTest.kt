package hivens.launcher.runtime

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeProvisionerTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val tmp: Path = Files.createTempDirectory("nexira-rt-test")
    private val librariesDir: Path = tmp.resolve("libraries")
    private val assetsDir: Path = tmp.resolve("assets")

    @AfterTest
    fun cleanup() {
        if (!Files.exists(tmp)) return
        Files.walk(tmp).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha1(s: String): String = sha1(s.toByteArray())

    private fun provisioner(client: HttpClient, osName: String = "Linux") = RuntimeProvisioner(
        librariesDir = librariesDir,
        assetsDir = assetsDir,
        clientProvider = HttpClientProvider { client },
        json = json,
        osName = osName,
        versionManifestUrl = MANIFEST_URL,
        resourcesBaseUrl = RES_BASE,
    )

    // -- pure logic -----------------------------------------------------------

    @Test
    fun `client jar lands at a synthetic minecraft coord under libraries`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
        assertEquals(
            "net/minecraft/minecraft/1.12.2/minecraft-1.12.2.jar",
            p.clientJarRelPath("1.12.2"),
        )
    }

    @Test
    fun `asset object path and url are content-addressed by hash prefix`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
        val hash = "abcd1234ef567890abcd1234ef567890abcd1234"
        assertEquals("objects/ab/$hash", p.assetObjectRelPath(hash))
        assertEquals("$RES_BASE/ab/$hash", p.assetObjectUrl(hash))
        assertEquals("indexes/1.12.json", p.assetIndexRelPath("1.12"))
    }

    @Test
    fun `rule evaluation keeps allowed libs and drops wrong-platform libs`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), osName = "Linux")
        assertTrue(p.isLibraryAllowed(emptyList()))
        assertTrue(p.isLibraryAllowed(listOf(MojangRule("allow"))))
        // allow-all then disallow-on-linux -> disallowed on linux.
        assertTrue(!p.isLibraryAllowed(listOf(MojangRule("allow"), MojangRule("disallow", MojangOs("linux")))))
        // default-disallow then allow-only-osx -> disallowed on linux.
        assertTrue(!p.isLibraryAllowed(listOf(MojangRule("disallow"), MojangRule("allow", MojangOs("osx")))))
    }

    @Test
    fun `planner maps libraries, client, and every asset object`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
        val version = MojangVersion(
            assetIndex = MojangAssetIndexRef(id = "1.12", sha1 = "x", url = "u"),
            downloads = MojangDownloads(MojangArtifact(sha1 = "c", size = 9, url = "https://piston/client.jar")),
            libraries = listOf(
                MojangLibrary(
                    name = "com.mojang:patchy:1.1",
                    downloads = MojangLibraryDownloads(
                        MojangArtifact("com/mojang/patchy/1.1/patchy-1.1.jar", "l", 6, "https://libs/patchy.jar"),
                    ),
                ),
                // dropped: disallowed on linux even though it has an artifact.
                MojangLibrary(
                    name = "win.only:lib:1",
                    downloads = MojangLibraryDownloads(MojangArtifact("win/only/lib/1/lib-1.jar", "w", 1, "https://libs/win.jar")),
                    rules = listOf(MojangRule("disallow"), MojangRule("allow", MojangOs("windows"))),
                ),
            ),
        )
        val index = MojangAssetIndex(objects = mapOf("minecraft/lang/en_us.lang" to MojangAssetObject("deadbeef", 4)))

        val tasks = p.planVanillaDownloads("1.12.2", version, index)

        // patchy lib + client jar + 1 asset object; the windows-only lib is dropped on linux.
        assertEquals(3, tasks.size)
        val byDest = tasks.associateBy { it.dest }
        assertTrue(byDest.keys.any { it == librariesDir.resolve("com/mojang/patchy/1.1/patchy-1.1.jar") })
        assertTrue(byDest.keys.any { it == librariesDir.resolve("net/minecraft/minecraft/1.12.2/minecraft-1.12.2.jar") })
        assertTrue(byDest.keys.any { it == assetsDir.resolve("objects/de/deadbeef") })
        assertTrue(byDest.keys.none { it.toString().contains("win") }, "wrong-platform lib must be dropped")
    }

    // -- end to end (MockEngine) ----------------------------------------------

    @Test
    fun `ensureVanilla downloads runtime, verifies sha1, returns libraries, idempotent`() = runTest {
        val libBytes = "PATCHY-JAR".toByteArray()
        val clientBytes = "CLIENT-JAR".toByteArray()
        val objBytes = "EN-US-LANG".toByteArray()
        val objHash = sha1(objBytes)

        val indexJson = """{"objects":{"minecraft/lang/en_us.lang":{"hash":"$objHash","size":${objBytes.size}}}}"""
        val indexSha = sha1(indexJson)

        val versionJson = """
            {
              "assetIndex": {"id":"1.12","sha1":"$indexSha","size":${indexJson.length},"totalSize":${objBytes.size},"url":"$INDEX_URL"},
              "downloads": {"client": {"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
              "libraries": [
                {"name":"com.mojang:patchy:1.1","downloads":{"artifact":{"path":"com/mojang/patchy/1.1/patchy-1.1.jar","sha1":"${sha1(libBytes)}","size":${libBytes.size},"url":"$LIB_URL"}}}
              ]
            }
        """.trimIndent()
        val manifestJson = """{"versions":[{"id":"1.12.2","url":"$VERSION_URL"}]}"""

        val requests = mutableListOf<String>()
        val engine = MockEngine { req ->
            val url = req.url.toString()
            requests += url
            when (url) {
                MANIFEST_URL -> respond(manifestJson, HttpStatusCode.OK, jsonHeaders)
                VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonHeaders)
                INDEX_URL -> respond(indexJson, HttpStatusCode.OK, jsonHeaders)
                LIB_URL -> respond(ByteReadChannel(libBytes), HttpStatusCode.OK)
                CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
                "$RES_BASE/${objHash.take(2)}/$objHash" -> respond(ByteReadChannel(objBytes), HttpStatusCode.OK)
                else -> respond("missing: $url", HttpStatusCode.NotFound)
            }
        }
        val p = provisioner(HttpClient(engine))

        val result = p.ensureVanilla("1.12.2")

        assertEquals("1.12", result.assetIndexId)
        assertEquals(librariesDir.resolve("net/minecraft/minecraft/1.12.2/minecraft-1.12.2.jar"), result.clientJar)
        assertTrue(result.clientJar.exists(), "client jar materialised")
        assertEquals("CLIENT-JAR", result.clientJar.readText())
        assertEquals("PATCHY-JAR", librariesDir.resolve("com/mojang/patchy/1.1/patchy-1.1.jar").readText())
        assertEquals("EN-US-LANG", assetsDir.resolve("objects/${objHash.take(2)}/$objHash").readText())
        assertTrue(assetsDir.resolve("indexes/1.12.json").exists(), "asset index persisted")
        // Vanilla libraries returned with coord + on-disk path (the merge base).
        assertEquals(1, result.libraries.size)
        assertEquals("com.mojang:patchy", result.libraries[0].coord.groupArtifact)
        assertEquals(librariesDir.resolve("com/mojang/patchy/1.1/patchy-1.1.jar"), result.libraries[0].path)

        // Second call: metadata is re-read, but the heavy files are skipped (present + right size).
        requests.clear()
        val again = p.ensureVanilla("1.12.2")
        assertEquals("1.12", again.assetIndexId)
        assertTrue(LIB_URL !in requests && CLIENT_URL !in requests, "downloaded jars must not be re-fetched, got: $requests")
        assertTrue(requests.none { it.startsWith(RES_BASE) }, "asset objects must not be re-fetched, got: $requests")
    }

    @Test
    fun `ensureVanilla throws on sha1 mismatch and leaves no partial file`() = runTest {
        val libBytes = "REAL-LIB".toByteArray()
        val indexJson = """{"objects":{}}"""
        val indexSha = sha1(indexJson)
        val versionJson = """
            {
              "assetIndex": {"id":"1.12","sha1":"$indexSha","size":${indexJson.length},"url":"$INDEX_URL"},
              "downloads": {"client": {"sha1":"deadbeefdeadbeefdeadbeefdeadbeefdeadbeef","size":1,"url":"$CLIENT_URL"}},
              "libraries": [
                {"name":"x:y:1","downloads":{"artifact":{"path":"x/y/1/y-1.jar","sha1":"0000000000000000000000000000000000000000","size":${libBytes.size},"url":"$LIB_URL"}}}
              ]
            }
        """.trimIndent()
        val manifestJson = """{"versions":[{"id":"1.12.2","url":"$VERSION_URL"}]}"""

        val engine = MockEngine { req ->
            when (req.url.toString()) {
                MANIFEST_URL -> respond(manifestJson, HttpStatusCode.OK, jsonHeaders)
                VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonHeaders)
                INDEX_URL -> respond(indexJson, HttpStatusCode.OK, jsonHeaders)
                LIB_URL -> respond(ByteReadChannel(libBytes), HttpStatusCode.OK) // hashes to != declared sha1
                CLIENT_URL -> respond(ByteReadChannel("c".toByteArray()), HttpStatusCode.OK)
                else -> respond("missing", HttpStatusCode.NotFound)
            }
        }
        val p = provisioner(HttpClient(engine))

        assertFailsWith<IOException> { p.ensureVanilla("1.12.2") }
        assertTrue(!librariesDir.resolve("x/y/1/y-1.jar").exists(), "bad download must not leave a file")
    }

    private companion object {
        const val MANIFEST_URL = "https://test.invalid/manifest.json"
        const val VERSION_URL = "https://test.invalid/1.12.2.json"
        const val INDEX_URL = "https://test.invalid/1.12.json"
        const val LIB_URL = "https://test.invalid/libs/patchy.jar"
        const val CLIENT_URL = "https://test.invalid/client.jar"
        const val RES_BASE = "https://res.invalid"
        val jsonHeaders = headersOf("Content-Type", "application/json")
    }
}
