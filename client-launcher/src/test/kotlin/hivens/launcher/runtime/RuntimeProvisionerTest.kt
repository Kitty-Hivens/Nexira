package hivens.launcher.runtime

import hivens.launcher.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.loader.LoaderProfile
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.runtime.loader.LoaderResolver
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

    // Pin the host arch: CI runners vary (macos-latest is arm64), and an
    // un-pinned os.arch flips acceptedNativeClassifiers to the -arm64 variant,
    // breaking tests that assert the x64 `natives-<os>` classifier.
    private fun provisioner(
        client: HttpClient,
        osName: String = "Linux",
        osArch: String = "amd64",
    ) = RuntimeProvisioner(
        librariesDir = librariesDir,
        assetsDir = assetsDir,
        clientProvider = HttpClientProvider { client },
        transfers = testTransferEngine(HttpClientProvider { client }),
        json = json,
        osName = osName,
        osArch = osArch,
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

    @Test
    fun `a library path climbing out of the libraries root is refused`() {
        // artifact.path comes from the per-version json, which carries no
        // signature of its own -- the sha1 chain hanging off it authenticates
        // the bytes, never the document that names them. So the destination is
        // the server's to choose unless it is bounded.
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
        val version = MojangVersion(
            assetIndex = MojangAssetIndexRef(id = "1.12", sha1 = "x", url = "u"),
            downloads = MojangDownloads(MojangArtifact(sha1 = "c", size = 9, url = "https://piston/client.jar")),
            libraries = listOf(
                MojangLibrary(
                    name = "evil:lib:1",
                    downloads = MojangLibraryDownloads(
                        MojangArtifact("../../../../.config/autostart/evil.desktop", "l", 6, "https://libs/evil"),
                    ),
                ),
            ),
        )
        val index = MojangAssetIndex(objects = emptyMap())

        assertFailsWith<IOException> { p.planVanillaDownloads("1.12.2", version, index) }
    }

    @Test
    fun `an asset hash climbing out of the assets root is refused`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }))
        val version = MojangVersion(
            assetIndex = MojangAssetIndexRef(id = "1.12", sha1 = "x", url = "u"),
            downloads = MojangDownloads(MojangArtifact(sha1 = "c", size = 9, url = "https://piston/client.jar")),
        )
        // The hash is pasted straight into the object path, so it decides where
        // the object lands as much as any explicit path field does.
        val index = MojangAssetIndex(objects = mapOf("x" to MojangAssetObject("../../../../evil", 4)))

        assertFailsWith<IOException> { p.planVanillaDownloads("1.12.2", version, index) }
    }

    @Test
    fun `native jar paths pick host-matching classifiers from both manifest shapes`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), osName = "Linux")
        val version = MojangVersion(
            assetIndex = MojangAssetIndexRef(id = "x", sha1 = "x", url = "u"),
            downloads = MojangDownloads(MojangArtifact(sha1 = "c", size = 1, url = "u")),
            libraries = listOf(
                // pre-1.19 shape: base artifact + per-os classifier natives. linux kept;
                // windows dropped; the sources classifier ignored entirely.
                MojangLibrary(
                    name = "org.lwjgl.lwjgl:lwjgl:2.9.4",
                    downloads = MojangLibraryDownloads(
                        artifact = MojangArtifact("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4.jar", "a", 1, "u"),
                        classifiers = mapOf(
                            "natives-linux" to MojangArtifact("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4-natives-linux.jar", "n", 1, "u"),
                            "natives-windows" to MojangArtifact("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4-natives-windows.jar", "w", 1, "u"),
                            "sources" to MojangArtifact("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4-sources.jar", "s", 1, "u"),
                        ),
                    ),
                ),
                // 1.19+ shape: the native is its own library, classifier in the coord,
                // gated by an os rule. linux kept.
                MojangLibrary(
                    name = "org.lwjgl:lwjgl:3.3.3:natives-linux",
                    downloads = MojangLibraryDownloads(
                        artifact = MojangArtifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar", "m", 1, "u"),
                    ),
                    rules = listOf(MojangRule("allow", MojangOs("linux"))),
                ),
                // 1.19+ windows native -- dropped on linux by the os rule.
                MojangLibrary(
                    name = "org.lwjgl:lwjgl:3.3.3:natives-windows",
                    downloads = MojangLibraryDownloads(
                        artifact = MojangArtifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar", "mw", 1, "u"),
                    ),
                    rules = listOf(MojangRule("allow", MojangOs("windows"))),
                ),
            ),
        )

        val natives = p.nativeLibraries(version).map { it.path }

        assertEquals(2, natives.size, "exactly the two linux natives, got $natives")
        assertTrue(natives.contains(librariesDir.resolve("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4-natives-linux.jar")))
        assertTrue(natives.contains(librariesDir.resolve("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar")))
        assertTrue(natives.none { it.toString().contains("windows") }, "wrong-OS natives must be dropped")
        assertTrue(natives.none { it.toString().contains("sources") }, "non-native classifiers must be ignored")
    }

    @Test
    fun `planner downloads the host classifier natives, not other platforms`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), osName = "Linux")
        val version = MojangVersion(
            assetIndex = MojangAssetIndexRef(id = "x", sha1 = "x", url = "u"),
            downloads = MojangDownloads(MojangArtifact(sha1 = "c", size = 1, url = "https://piston/client.jar")),
            libraries = listOf(
                MojangLibrary(
                    name = "org.lwjgl.lwjgl:lwjgl:2.9.4",
                    downloads = MojangLibraryDownloads(
                        artifact = MojangArtifact("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4.jar", "a", 1, "https://libs/base.jar"),
                        classifiers = mapOf(
                            "natives-linux" to MojangArtifact("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4-natives-linux.jar", "n", 1, "https://libs/nat-linux.jar"),
                            "natives-windows" to MojangArtifact("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4-natives-windows.jar", "w", 1, "https://libs/nat-win.jar"),
                        ),
                    ),
                ),
            ),
        )
        val index = MojangAssetIndex(objects = emptyMap())

        val dests = p.planVanillaDownloads("2.9.4", version, index).map { it.dest }

        assertTrue(dests.contains(librariesDir.resolve("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4.jar")), "base jar downloaded")
        assertTrue(dests.contains(librariesDir.resolve("org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4-natives-linux.jar")), "host natives downloaded")
        assertTrue(dests.none { it.toString().contains("natives-windows") }, "wrong-OS natives must not be downloaded")
    }

    @Test
    fun `vanilla libraries keep the host native and drop foreign-platform natives`() {
        val p = provisioner(HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), osName = "Linux")
        val version = MojangVersion(
            assetIndex = MojangAssetIndexRef(id = "x", sha1 = "x", url = "u"),
            downloads = MojangDownloads(MojangArtifact(sha1 = "c", size = 1, url = "u")),
            libraries = listOf(
                MojangLibrary(
                    name = "com.example:plain:1",
                    downloads = MojangLibraryDownloads(MojangArtifact("com/example/plain/1/plain-1.jar", "p", 1, "u")),
                ),
                MojangLibrary(
                    name = "org.lwjgl:lwjgl:3.3.3:natives-linux",
                    downloads = MojangLibraryDownloads(MojangArtifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar", "l", 1, "u")),
                    rules = listOf(MojangRule("allow", MojangOs("linux"))),
                ),
                // Same OS, foreign arch: passes the os.name rule but must NOT land on
                // -cp -- on the module path it would collide as a second org.lwjgl.natives.
                MojangLibrary(
                    name = "org.lwjgl:lwjgl:3.3.3:natives-linux-arm64",
                    downloads = MojangLibraryDownloads(MojangArtifact("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux-arm64.jar", "a", 1, "u")),
                    rules = listOf(MojangRule("allow", MojangOs("linux"))),
                ),
            ),
        )

        val libs = p.vanillaLibraries(version)
        val classifiers = libs.map { it.coord.classifier }
        assertEquals(2, libs.size, "plain lib + host native only, got $classifiers")
        assertTrue(libs.any { it.coord.classifier == null && it.path.toString().contains("plain-1.jar") }, "normal lib kept")
        assertTrue("natives-linux" in classifiers, "host native kept -- the module graph needs it")
        assertTrue("natives-linux-arm64" !in classifiers, "foreign-arch native must be dropped from the classpath")
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

        // Second call is FULLY OFFLINE: the version json is cached, the asset
        // index sha already matches on disk, and every heavy file is present, so
        // nothing touches the network. This is the offline-launch guarantee.
        requests.clear()
        val again = p.ensureVanilla("1.12.2")
        assertEquals("1.12", again.assetIndexId)
        assertTrue(requests.isEmpty(), "a warm relaunch must make ZERO network requests, got: $requests")
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

    // -- javaMajor declare-model (loader > vanilla > heuristic precedence) ----

    @Test
    fun `ensureVanilla captures Mojang javaVersion (and no-loader runtime inherits it)`() = runTest {
        val clientBytes = "C".toByteArray()
        val indexJson = """{"objects":{}}"""
        val indexSha = sha1(indexJson)
        // 1.17+ vanilla json shape: carries `javaVersion.majorVersion` directly.
        val versionJson = """
            {
              "assetIndex": {"id":"17","sha1":"$indexSha","size":${indexJson.length},"url":"$INDEX_URL"},
              "downloads": {"client": {"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
              "javaVersion": {"majorVersion": 21},
              "libraries": []
            }
        """.trimIndent()
        val manifestJson = """{"versions":[{"id":"1.21.1","url":"$VERSION_URL"}]}"""

        val engine = MockEngine { req ->
            when (req.url.toString()) {
                MANIFEST_URL -> respond(manifestJson, HttpStatusCode.OK, jsonHeaders)
                VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonHeaders)
                INDEX_URL -> respond(indexJson, HttpStatusCode.OK, jsonHeaders)
                CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
                else -> respond("missing", HttpStatusCode.NotFound)
            }
        }
        val p = provisioner(HttpClient(engine))

        val vanilla = p.ensureVanilla("1.21.1")
        assertEquals(21, vanilla.javaMajor, "Mojang's javaVersion.majorVersion captured from the version json")

        val resolved = p.ensureRuntime(mcVersion = "1.21.1", loaderName = null, loaderVersion = "")
        assertEquals(21, resolved.javaMajor, "no-loader runtime inherits vanilla's declared major")
    }

    @Test
    fun `loader profile declared javaMajor wins over Mojang's vanilla declaration`() = runTest {
        val clientBytes = "C".toByteArray()
        val indexJson = """{"objects":{}}"""
        val indexSha = sha1(indexJson)
        // Vanilla declares 21; a loader profile declares 25 -- loader wins. This is
        // the forward shape Cleanroom needs (1.12.2 on Cleanroom -> Java 25, not 8).
        val versionJson = """
            {
              "assetIndex": {"id":"17","sha1":"$indexSha","size":${indexJson.length},"url":"$INDEX_URL"},
              "downloads": {"client": {"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
              "javaVersion": {"majorVersion": 21},
              "libraries": []
            }
        """.trimIndent()
        val manifestJson = """{"versions":[{"id":"1.21.1","url":"$VERSION_URL"}]}"""

        val engine = MockEngine { req ->
            when (req.url.toString()) {
                MANIFEST_URL -> respond(manifestJson, HttpStatusCode.OK, jsonHeaders)
                VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonHeaders)
                INDEX_URL -> respond(indexJson, HttpStatusCode.OK, jsonHeaders)
                CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
                else -> respond("missing", HttpStatusCode.NotFound)
            }
        }
        // Stub resolver returns a profile declaring Java 25; empty libraries -> no extra HTTP.
        val stub = object : LoaderResolver {
            override val loaderId = "stub"
            override suspend fun resolve(mcVersion: String, loaderVersion: String) =
                LoaderProfile(libraries = emptyList(), mainClass = "fake.Main", javaMajor = 25)
        }
        val p = RuntimeProvisioner(
            librariesDir = librariesDir,
            assetsDir = assetsDir,
            clientProvider = HttpClientProvider { HttpClient(engine) },
            transfers = testTransferEngine(HttpClientProvider { HttpClient(engine) }),
            json = json,
            loaderRegistry = LoaderRegistry(listOf(stub)),
            osName = "Linux",
            versionManifestUrl = MANIFEST_URL,
            resourcesBaseUrl = RES_BASE,
        )

        val resolved = p.ensureRuntime(mcVersion = "1.21.1", loaderName = "stub", loaderVersion = "any")
        assertEquals(25, resolved.javaMajor, "loader profile.javaMajor must win over vanilla.javaMajor")
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
