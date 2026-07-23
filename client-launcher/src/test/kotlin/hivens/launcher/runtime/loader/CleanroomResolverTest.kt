package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `removeFromBase drops vanilla LWJGL2 and nothing else`() {
        val p = realProfile()
        assertTrue(p.removeFromBase(MavenCoord.parse("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209")))
        assertTrue(p.removeFromBase(MavenCoord.parse("org.lwjgl.lwjgl:lwjgl_util:2.9.4-nightly-20150209")))
        assertFalse(p.removeFromBase(MavenCoord.parse("org.lwjgl:lwjgl:3.4.1")))
        assertFalse(p.removeFromBase(MavenCoord.parse("com.google.guava:guava:33.6.0-jre")))
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
}
