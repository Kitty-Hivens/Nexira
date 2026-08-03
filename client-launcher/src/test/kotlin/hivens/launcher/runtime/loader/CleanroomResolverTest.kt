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
}
