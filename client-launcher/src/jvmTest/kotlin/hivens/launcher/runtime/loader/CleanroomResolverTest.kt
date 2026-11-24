package hivens.launcher.runtime.loader

import hivens.test.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises [CleanroomResolver.buildProfile] against the real installer
 * `version.json` shipped by Cleanroom 0.6.4-alpha (checked in under
 * resources/loader/cleanroom). No network: the profile translation is pure over
 * the parsed library set.
 */
class CleanroomResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    // buildProfile never touches the http client; a stub keeps the ctor happy.
    private val resolver = CleanroomResolver(
        clientProvider = HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) },
        transfers = testTransferEngine(HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) }),
        json = json,
    )

    private fun realProfile(): LoaderProfile {
        val text = javaClass.getResourceAsStream("/loader/cleanroom/cleanroom-0.6.4-alpha-version.json")
            ?.readBytes()?.decodeToString()
            ?: error("fixture missing")
        val version = json.decodeFromString(LoaderVersionJson.serializer(), text)
        val specs = version.libraries.map { lib ->
            val coord = MavenCoord.parse(lib.name)
            val art = lib.downloads!!.artifact!!
            LibrarySpec(coord, url = art.url.ifBlank { "https://bundled.invalid/${art.path}" }, sha1 = art.sha1, size = art.size)
        }
        return resolver.buildProfile(version.mainClass, version.minecraftArguments, specs)
    }

    @Test
    fun `main class and tweaker come from the installer json`() {
        val p = realProfile()
        assertEquals("top.outlands.foundation.boot.Foundation", p.mainClass)
        assertEquals(
            listOf("--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker"),
            p.gameArgs,
        )
        assertEquals(25, p.javaMajor)
    }

    @Test
    fun `the profile replaces the vanilla library set wholesale`() {
        // Cleanroom is self-contained; dropping the vanilla libs is what keeps the
        // old oshi/icu/netty twins from shadowing its own.
        assertTrue(realProfile().replacesVanillaLibraries)
    }

    @Test
    fun `natives override is every lwjgl3 native across platforms, and only natives`() {
        val natives = realProfile().nativesOverride ?: error("expected a native override")
        assertTrue(natives.isNotEmpty())
        assertTrue(natives.all { (it.coord.classifier ?: "").startsWith("natives") }, "only natives-* classifiers")
        assertTrue(natives.all { it.coord.group == "org.lwjgl" }, "all LWJGL3")
        // the real instance ships 7 modules x 8 platform classifiers.
        assertEquals(56, natives.size)
        assertTrue(natives.any { it.coord.classifier == "natives-linux" })
        assertTrue(natives.any { it.coord.classifier == "natives-windows" })
        assertTrue(natives.any { it.coord.classifier == "natives-macos-arm64" })
    }

    @Test
    fun `classpath overlay has LWJGL3 base modules, the loader libs, no natives, no LWJGL2`() {
        val libs = realProfile().libraries
        // LWJGL3 base modules (no classifier) stay on the classpath.
        assertTrue(libs.any { it.coord.groupArtifact == "org.lwjgl:lwjgl-glfw" && it.coord.classifier == null })
        assertTrue(libs.any { it.coord.groupArtifact == "org.lwjgl:lwjgl" && it.coord.version == "3.4.1-unsafe" && it.coord.classifier == null })
        // the loader's own libraries came through.
        assertTrue(libs.any { it.coord.groupArtifact == "top.outlands:foundation" })
        assertTrue(libs.any { it.coord.groupArtifact == "com.cleanroommc:cleanroom" })
        // no natives leaked into the classpath, and no LWJGL2.
        assertTrue(libs.none { (it.coord.classifier ?: "").startsWith("natives") })
        assertTrue(libs.none { it.coord.group == "org.lwjgl.lwjgl" })
    }

    @Test
    fun `each host takes the text2speech build its rules name`() {
        val text = javaClass.getResourceAsStream("/loader/cleanroom/cleanroom-0.6.4-alpha-version.json")
            ?.readBytes()?.decodeToString() ?: error("fixture missing")
        val libraries = json.decodeFromString(LoaderVersionJson.serializer(), text).libraries
        val offline = HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) }
        fun speech(os: String, arm64: Boolean): Set<String> =
            CleanroomResolver(offline, testTransferEngine(offline), json, mojangOs = os, arm64 = arm64)
                .hostLibraries(libraries)
                .filter { it.name.startsWith("com.mojang:text2speech:") }
                .mapTo(HashSet()) { it.name.substringAfterLast(':') }

        assertEquals(setOf("1.10.3"), speech("linux", arm64 = false))
        assertEquals(setOf("1.10.3"), speech("osx", arm64 = false), "an Intel Mac is not Apple Silicon")
        assertEquals(setOf("1.11.3"), speech("osx", arm64 = true))
    }

    /**
     * Resolves the real 0.7.16 `version.json` through an installer built around it, the jars it
     * bundles standing in as empty files.
     */
    private suspend fun resolved07(os: String, arm64: Boolean): LoaderProfile {
        val text = javaClass.getResourceAsStream("/loader/cleanroom/cleanroom-0.7.16-alpha-version.json")
            ?.readBytes() ?: error("fixture missing")
        val libraries = json.decodeFromString(LoaderVersionJson.serializer(), text.decodeToString()).libraries
        val installer = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("version.json")); zip.write(text); zip.closeEntry()
                libraries.mapNotNull { it.downloads?.artifact }.filter { it.url.isBlank() }.map { it.path }.distinct().forEach {
                    zip.putNextEntry(ZipEntry("maven/$it")); zip.closeEntry()
                }
            }
        }.toByteArray()
        val provider = HttpClientProvider { HttpClient(MockEngine { respond(ByteReadChannel(installer), HttpStatusCode.OK) }) }
        return CleanroomResolver(provider, testTransferEngine(provider), json, releaseBase = "https://cr.invalid/dl", mojangOs = os, arm64 = arm64)
            .resolve("1.12.2", "0.7.16-alpha")
    }

    @Test
    fun `a native listed under its library's plain name from 0_7 is the native of this host and no other`() = runTest {
        suspend fun natives(os: String, arm64: Boolean) = resolved07(os, arm64).nativesOverride.orEmpty().map { it.coord }

        listOf(
            Triple("windows", false, setOf("natives-windows")),
            Triple("windows", true, setOf("natives-windows-arm64")),
            Triple("linux", false, setOf("natives-linux")),
            Triple("linux", true, setOf("natives-linux-arm64")),
            Triple("osx", true, setOf("natives-macos-arm64")),
        ).forEach { (os, arm64, classifiers) ->
            assertEquals(classifiers, natives(os, arm64).mapTo(HashSet()) { it.classifier.orEmpty() }, "$os arm64=$arm64")
        }
        // Mojang's own jars of the time spell an Intel Mac the older way.
        assertTrue(setOf("natives-macos", "natives-osx").containsAll(natives("osx", false).map { it.classifier.orEmpty() }))

        val windows = natives("windows", false).map { it.groupArtifact }
        assertTrue(windows.containsAll(listOf("org.lwjgl:lwjgl", "org.lwjgl:lwjgl-opengl", "org.lwjgl:lwjgl-openal")), "got $windows")
        assertEquals(windows.distinct(), windows, "each native once")
    }

    @Test
    fun `from 0_7 the base jar stays on the classpath beside its native, and only this host's architecture does`() = runTest {
        val libs = resolved07("linux", arm64 = false).libraries.map { it.coord }

        assertTrue(libs.any { it.groupArtifact == "org.lwjgl:lwjgl" && it.classifier == null })
        assertTrue(libs.any { it.groupArtifact == "com.mojang:text2speech" && it.classifier == null })
        assertTrue(libs.none { it.nativeClassifier != null }, "no natives leaked into the classpath")
        val epoll = libs.filter { it.groupArtifact == "io.netty:netty-transport-native-epoll" }.map { it.classifier }
        assertTrue("linux-x86_64" in epoll, "got $epoll")
        assertTrue(epoll.none { it == "linux-aarch_64" || it == "linux-riscv64" }, "got $epoll")
    }

    @Test
    fun `registry resolves the cleanroom loader id case-insensitively`() {
        val registry = LoaderRegistry(listOf(resolver))
        assertSame(resolver, registry.resolverFor("cleanroom"))
        assertSame(resolver, registry.resolverFor("Cleanroom"))
        assertNull(registry.resolverFor("forge"), "an unrelated loader must not match")
        assertNull(registry.resolverFor("vanilla"), "vanilla means no overlay")
    }

    @Test
    fun `resolve fails with a message naming the loader, version and missing artifact`() = runTest {
        // A tester forwarding this line must be able to see what broke without a
        // stacktrace: loader, version, and the artifact that was absent.
        val badInstaller = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use {
                it.putNextEntry(ZipEntry("readme.txt")); it.write("no version json here".toByteArray()); it.closeEntry()
            }
        }.toByteArray()
        val installerUrl = "https://cr.invalid/dl/9.9.9-alpha/cleanroom-9.9.9-alpha-installer.jar"
        // One provider for both, since the installer download is the request under
        // test: an engine over its own mock would serve something else entirely.
        val provider = HttpClientProvider {
            HttpClient(MockEngine { req ->
                if (req.url.toString() == installerUrl) respond(ByteReadChannel(badInstaller), HttpStatusCode.OK)
                else respond("missing", HttpStatusCode.NotFound)
            })
        }
        val net = CleanroomResolver(
            clientProvider = provider,
            transfers = testTransferEngine(provider),
            json = json,
            releaseBase = "https://cr.invalid/dl",
        )

        val ex = assertFailsWith<IOException> { net.resolve("1.12.2", "9.9.9-alpha") }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("cleanroom"), "names the loader: $msg")
        assertTrue(msg.contains("9.9.9-alpha"), "names the version: $msg")
        assertTrue(msg.contains("version.json"), "names what is missing: $msg")
    }

    /** Layered onto another Minecraft version it assembled a classpath that could only crash. */
    @Test
    fun `Cleanroom refuses any Minecraft but 1_12_2, and a blank version, before fetching anything`() = runTest {
        val wrongMc = runCatching { resolver.resolve("1.20.1", "0.3.0") }.exceptionOrNull()
        val blank = runCatching { resolver.resolve("1.12.2", "") }.exceptionOrNull()

        assertTrue(wrongMc?.message.orEmpty().contains("1.12.2"), "got $wrongMc")
        assertTrue(blank?.message.orEmpty().contains("version"), "got $blank")
    }
}
