package hivens.launcher.runtime

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.loader.LibrarySpec
import hivens.launcher.runtime.loader.LoaderProfile
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.runtime.loader.LoaderResolver
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.mergeLibraries
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeLoaderTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val tmp: Path = Files.createTempDirectory("nexira-loader-test")
    private val librariesDir: Path = tmp.resolve("libraries")
    private val assetsDir: Path = tmp.resolve("assets")

    @AfterTest
    fun cleanup() {
        if (!Files.exists(tmp)) return
        Files.walk(tmp).use { s -> s.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } } }
    }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private fun lib(coord: String, path: String) =
        ResolvedLibrary(MavenCoord.parse(coord), Paths.get(path))

    // -- mergeLibraries (pure) ------------------------------------------------

    @Test
    fun `overlay wins on group-artifact collision, keeps the rest`() {
        val base = listOf(lib("org.ow2.asm:asm:5.0.3", "/v/asm-5.jar"), lib("com.google.guava:guava:21.0", "/v/guava.jar"))
        val overlay = listOf(lib("org.ow2.asm:asm:9.9", "/f/asm-9.jar"), lib("net.fabricmc:fabric-loader:0.16", "/f/fl.jar"))

        val merged = mergeLibraries(base, overlay)

        assertEquals(3, merged.size)
        val asm = merged.first { it.coord.groupArtifact == "org.ow2.asm:asm" }
        assertEquals("9.9", asm.coord.version)
        assertEquals(Paths.get("/f/asm-9.jar"), asm.path)
        assertTrue(merged.any { it.coord.groupArtifact == "com.google.guava:guava" })
        assertTrue(merged.any { it.coord.groupArtifact == "net.fabricmc:fabric-loader" })
    }

    @Test
    fun `keeps a library and its natives classifier as separate entries`() {
        // Modern MC lists a lib's base jar and its natives jar as two entries
        // with the same group:artifact. Keying dedup on group:artifact alone
        // drops the base -> "Module org.lwjgl not found, required by
        // org.lwjgl.natives" at BootstrapLauncher. The classifier must be in
        // the key so both survive.
        val base = listOf(
            lib("org.lwjgl:lwjgl:3.3.3", "/v/lwjgl.jar"),
            lib("org.lwjgl:lwjgl:3.3.3:natives-linux", "/v/lwjgl-natives.jar"),
        )

        val merged = mergeLibraries(base, emptyList())

        assertEquals(2, merged.size, "base jar and natives jar must both survive")
        assertTrue(merged.any { it.coord.classifier == null }, "base lwjgl present")
        assertTrue(merged.any { it.coord.classifier == "natives-linux" }, "natives lwjgl present")
    }

    // -- ensureRuntime dispatch (MockEngine + fake resolver) ------------------

    private val patchyBytes = "PATCHY".toByteArray()
    private val clientBytes = "CLIENT".toByteArray()
    private val loaderBytes = "FABRIC-LOADER".toByteArray()

    private fun engine(requests: MutableList<String>) = MockEngine { req ->
        val url = req.url.toString()
        requests += url
        val emptyIndex = """{"objects":{}}"""
        val versionJson = """
            {"assetIndex":{"id":"1.12","sha1":"${sha1(emptyIndex.toByteArray())}","size":${emptyIndex.length},"url":"$INDEX_URL"},
             "downloads":{"client":{"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
             "libraries":[{"name":"com.mojang:patchy:1.1","downloads":{"artifact":{"path":"com/mojang/patchy/1.1/patchy-1.1.jar","sha1":"${sha1(patchyBytes)}","size":${patchyBytes.size},"url":"$LIB_URL"}}}]}
        """.trimIndent()
        when (url) {
            MANIFEST_URL -> respond("""{"versions":[{"id":"1.12.2","url":"$VERSION_URL"}]}""", HttpStatusCode.OK, jsonH)
            VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonH)
            INDEX_URL -> respond(emptyIndex, HttpStatusCode.OK, jsonH)
            LIB_URL -> respond(ByteReadChannel(patchyBytes), HttpStatusCode.OK)
            CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
            LOADER_LIB_URL -> respond(ByteReadChannel(loaderBytes), HttpStatusCode.OK)
            else -> respond("missing $url", HttpStatusCode.NotFound)
        }
    }

    private fun provisioner(registry: LoaderRegistry, requests: MutableList<String>) = RuntimeProvisioner(
        librariesDir = librariesDir, assetsDir = assetsDir,
        clientProvider = HttpClientProvider { HttpClient(engine(requests)) },
        json = json, loaderRegistry = registry, osName = "Linux",
        versionManifestUrl = MANIFEST_URL, resourcesBaseUrl = RES_BASE,
    )

    @Test
    fun `ensureRuntime merges a loader overlay onto vanilla`() = runTest {
        val fabricProfile = LoaderProfile(
            libraries = listOf(LibrarySpec(MavenCoord.parse("net.fabricmc:fabric-loader:0.16.0"), LOADER_LIB_URL, sha1(loaderBytes), loaderBytes.size.toLong())),
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            gameArgs = listOf("--fabric"),
        )
        val resolver = object : LoaderResolver {
            override val loaderId = "fabric"
            override suspend fun resolve(mcVersion: String, loaderVersion: String) = fabricProfile
        }
        val requests = mutableListOf<String>()
        val p = provisioner(LoaderRegistry(listOf(resolver)), requests)

        val rt = p.ensureRuntime("1.12.2", "fabric", "0.16.0")

        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", rt.mainClass)
        assertEquals("1.12", rt.assetIndexId)
        assertEquals(listOf("--fabric"), rt.gameArgs)
        // vanilla patchy + fabric-loader, both present + downloaded.
        assertTrue(rt.libraries.any { it.coord.groupArtifact == "com.mojang:patchy" })
        assertTrue(rt.libraries.any { it.coord.groupArtifact == "net.fabricmc:fabric-loader" })
        assertEquals("FABRIC-LOADER", librariesDir.resolve("net/fabricmc/fabric-loader/0.16.0/fabric-loader-0.16.0.jar").toFile().readText())
    }

    @Test
    fun `ensureRuntime with vanilla loader applies no overlay`() = runTest {
        val requests = mutableListOf<String>()
        val p = provisioner(LoaderRegistry(emptyList()), requests)

        val rt = p.ensureRuntime("1.12.2", "vanilla", "")

        assertEquals(RuntimeProvisioner.VANILLA_MAIN_CLASS, rt.mainClass)
        assertEquals(1, rt.libraries.size) // just vanilla patchy
        assertTrue(requests.none { it == LOADER_LIB_URL }, "no loader libs fetched for vanilla")
    }

    // -- removeFromBase + nativesOverride (the Cleanroom / lwjgl3ify capability) --

    private val lwjgl2Bytes = "LWJGL2".toByteArray()
    private val lwjgl3Bytes = "LWJGL3".toByteArray()
    private val nativeBytes = "LWJGL3-NATIVES".toByteArray()

    /** Vanilla 1.12.2-shaped base that carries LWJGL2 (`org.lwjgl.lwjgl` group)
     *  alongside patchy, so a loader can exercise the cross-group swap. */
    private fun swapEngine(requests: MutableList<String>) = MockEngine { req ->
        val url = req.url.toString()
        requests += url
        val emptyIndex = """{"objects":{}}"""
        val versionJson = """
            {"assetIndex":{"id":"1.12","sha1":"${sha1(emptyIndex.toByteArray())}","size":${emptyIndex.length},"url":"$INDEX_URL"},
             "downloads":{"client":{"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
             "libraries":[
               {"name":"com.mojang:patchy:1.1","downloads":{"artifact":{"path":"com/mojang/patchy/1.1/patchy-1.1.jar","sha1":"${sha1(patchyBytes)}","size":${patchyBytes.size},"url":"$LIB_URL"}}},
               {"name":"org.lwjgl.lwjgl:lwjgl:2.9.4","downloads":{"artifact":{"path":"org/lwjgl/lwjgl/lwjgl/2.9.4/lwjgl-2.9.4.jar","sha1":"${sha1(lwjgl2Bytes)}","size":${lwjgl2Bytes.size},"url":"$LWJGL2_URL"}}}
             ]}
        """.trimIndent()
        when (url) {
            MANIFEST_URL -> respond("""{"versions":[{"id":"1.12.2","url":"$VERSION_URL"}]}""", HttpStatusCode.OK, jsonH)
            VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonH)
            INDEX_URL -> respond(emptyIndex, HttpStatusCode.OK, jsonH)
            LIB_URL -> respond(ByteReadChannel(patchyBytes), HttpStatusCode.OK)
            CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
            LWJGL2_URL -> respond(ByteReadChannel(lwjgl2Bytes), HttpStatusCode.OK)
            LWJGL3_URL -> respond(ByteReadChannel(lwjgl3Bytes), HttpStatusCode.OK)
            NATIVE_URL -> respond(ByteReadChannel(nativeBytes), HttpStatusCode.OK)
            else -> respond("missing $url", HttpStatusCode.NotFound)
        }
    }

    private fun swapProvisioner(registry: LoaderRegistry, requests: MutableList<String>) = RuntimeProvisioner(
        librariesDir = librariesDir, assetsDir = assetsDir,
        clientProvider = HttpClientProvider { HttpClient(swapEngine(requests)) },
        json = json, loaderRegistry = registry, osName = "Linux",
        versionManifestUrl = MANIFEST_URL, resourcesBaseUrl = RES_BASE,
    )

    private fun swapResolver(profile: LoaderProfile) = object : LoaderResolver {
        override val loaderId = "cleanroom"
        override suspend fun resolve(mcVersion: String, loaderVersion: String) = profile
    }

    @Test
    fun `removeFromBase strips vanilla LWJGL2 across group while the overlay adds LWJGL3`() = runTest {
        val profile = LoaderProfile(
            libraries = listOf(LibrarySpec(MavenCoord.parse("org.lwjgl:lwjgl:3.3.3"), LWJGL3_URL, sha1(lwjgl3Bytes), lwjgl3Bytes.size.toLong())),
            mainClass = "cleanroom.Foundation",
            removeFromBase = { it.group == "org.lwjgl.lwjgl" },
        )
        val p = swapProvisioner(LoaderRegistry(listOf(swapResolver(profile))), mutableListOf())

        val rt = p.ensureRuntime("1.12.2", "cleanroom", "0.3.0")

        assertTrue(rt.libraries.none { it.coord.group == "org.lwjgl.lwjgl" }, "vanilla LWJGL2 must be dropped")
        assertTrue(rt.libraries.any { it.coord.groupArtifact == "org.lwjgl:lwjgl" }, "LWJGL3 (different group) must be present")
        assertTrue(rt.libraries.any { it.coord.groupArtifact == "com.mojang:patchy" }, "an unrelated base lib survives the removal")
    }

    @Test
    fun `nativesOverride becomes the runtime native set`() = runTest {
        val nativeSpec = LibrarySpec(MavenCoord.parse("org.lwjgl:lwjgl:3.3.3:natives-linux"), NATIVE_URL, sha1(nativeBytes), nativeBytes.size.toLong())
        val profile = LoaderProfile(
            libraries = emptyList(),
            mainClass = "cleanroom.Foundation",
            nativesOverride = listOf(nativeSpec),
        )
        val p = swapProvisioner(LoaderRegistry(listOf(swapResolver(profile))), mutableListOf())

        val rt = p.ensureRuntime("1.12.2", "cleanroom", "0.3.0")

        assertEquals(
            listOf(librariesDir.resolve("org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar")),
            rt.natives,
        )
        assertEquals("LWJGL3-NATIVES", rt.natives.single().toFile().readText())
    }

    @Test
    fun `default profile removes nothing and inherits vanilla natives`() = runTest {
        // The additive shape every existing loader uses: removeFromBase and
        // nativesOverride left at their defaults must change neither the merged
        // library set nor the native set.
        val profile = LoaderProfile(
            libraries = listOf(LibrarySpec(MavenCoord.parse("org.lwjgl:lwjgl:3.3.3"), LWJGL3_URL, sha1(lwjgl3Bytes), lwjgl3Bytes.size.toLong())),
            mainClass = "cleanroom.Foundation",
        )
        val p = swapProvisioner(LoaderRegistry(listOf(swapResolver(profile))), mutableListOf())

        val rt = p.ensureRuntime("1.12.2", "cleanroom", "0.3.0")

        assertTrue(rt.libraries.any { it.coord.groupArtifact == "com.mojang:patchy" })
        assertTrue(rt.libraries.any { it.coord.group == "org.lwjgl.lwjgl" }, "no removal by default -- vanilla LWJGL2 stays")
        assertTrue(rt.natives.isEmpty(), "no override -- inherits vanilla's native set (empty in this fixture)")
    }

    private companion object {
        const val MANIFEST_URL = "https://t.invalid/manifest.json"
        const val VERSION_URL = "https://t.invalid/1.12.2.json"
        const val INDEX_URL = "https://t.invalid/1.12.json"
        const val LIB_URL = "https://t.invalid/patchy.jar"
        const val CLIENT_URL = "https://t.invalid/client.jar"
        const val LOADER_LIB_URL = "https://t.invalid/fabric-loader.jar"
        const val LWJGL2_URL = "https://t.invalid/lwjgl2.jar"
        const val LWJGL3_URL = "https://t.invalid/lwjgl3.jar"
        const val NATIVE_URL = "https://t.invalid/lwjgl3-natives-linux.jar"
        const val RES_BASE = "https://res.invalid"
        val jsonH = headersOf("Content-Type", "application/json")
    }
}
