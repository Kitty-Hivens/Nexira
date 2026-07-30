package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.MavenCoord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises [Lwjgl3ifyResolver.buildProfile] against the real release
 * `version.json` for lwjgl3ify 3.0.29 (checked in under
 * resources/loader/lwjgl3ify), on a Linux host. No network: the translation is
 * pure over the parsed profile.
 */
class Lwjgl3ifyResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val resolver = Lwjgl3ifyResolver(
        clientProvider = HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.OK) }) },
        json = json,
        osName = "Linux",
    )

    private fun realProfile(): LoaderProfile {
        val text = javaClass.getResourceAsStream("/loader/lwjgl3ify/lwjgl3ify-3.0.29-version.json")
            ?.readBytes()?.decodeToString()
            ?: error("fixture missing")
        return resolver.buildProfile(json.decodeFromString(LoaderVersionJson.serializer(), text))
    }

    @Test
    fun `main class is RFB and the FML tweaker comes from the game args`() {
        val p = realProfile()
        assertEquals("com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread", p.mainClass)
        assertEquals(listOf("--tweakClass", "cpw.mods.fml.common.launcher.FMLTweaker"), p.gameArgs)
        assertEquals(21, p.javaMajor)
    }

    @Test
    fun `jvm args keep the RFB system classloader and add-opens, drop the launcher-owned tokens`() {
        val jvm = realProfile().jvmArgs
        assertTrue(
            jvm.contains("-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader"),
            "RFB system classloader arg kept",
        )
        assertTrue(jvm.any { it == "--add-opens" }, "add-opens block kept")
        // launcher emits its own -cp / natives path; those must be gone...
        assertFalse(jvm.contains("-cp"), "own -cp dropped")
        assertFalse(jvm.any { it.startsWith("-Djava.library.path") }, "own java.library.path dropped")
        // ...and nothing with a placeholder survives, so the flat-classpath path runs.
        assertTrue(jvm.none { it.contains("\${") }, "no residual placeholders: $jvm")
    }

    @Test
    fun `os rules drop a Windows-only jvm arg on a Linux host`() {
        // The version.json gates -XX:HeapDumpPath=...javaw.exe... behind a windows rule.
        assertTrue(
            realProfile().jvmArgs.none { it.contains("HeapDumpPath") && it.contains("javaw.exe") },
            "windows-ruled arg must be dropped on linux",
        )
    }

    @Test
    fun `removeFromBase drops vanilla LWJGL2 and the commons forgePatches supersedes`() {
        val p = realProfile()
        // LWJGL2 across the whole group -> LWJGL3 is the only LWJGL on -cp.
        assertTrue(p.removeFromBase(MavenCoord.parse("org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20130708-debug3")))
        assertFalse(p.removeFromBase(MavenCoord.parse("org.lwjgl:lwjgl-opengl:3.4.2")))
        // The stale vanilla commons whose newer copies ship inside forgePatches;
        // left on -cp they shadow it and the Pack200 unpacker dies on
        // BoundedInputStream.builder().
        assertTrue(p.removeFromBase(MavenCoord.parse("commons-io:commons-io:2.4")))
        assertTrue(p.removeFromBase(MavenCoord.parse("org.apache.commons:commons-compress:1.8.1")))
        assertTrue(p.removeFromBase(MavenCoord.parse("org.apache.commons:commons-lang3:3.1")))
        // Not superseded -- these stay on the base classpath.
        assertFalse(p.removeFromBase(MavenCoord.parse("commons-logging:commons-logging:1.1.3")))
        assertFalse(p.removeFromBase(MavenCoord.parse("commons-codec:commons-codec:1.9")))
        assertFalse(p.removeFromBase(MavenCoord.parse("net.minecraftforge:forge:1.7.10-10.13.4.1614-1.7.10")))
    }

    @Test
    fun `name-encoded LWJGL3 natives route to the override, all under org lwjgl`() {
        val natives = realProfile().nativesOverride ?: error("expected a native override")
        assertTrue(natives.isNotEmpty())
        assertTrue(natives.all { it.coord.nativeClassifier != null }, "every override entry is a native")
        assertTrue(natives.all { it.coord.group == "org.lwjgl" }, "all LWJGL3")
        assertTrue(natives.any { it.coord.artifact == "lwjgl-opengl-natives-linux" }, "name-encoded native present")
    }

    @Test
    fun `classpath carries LWJGL3 base, forge and the RFB patches jar, but no natives or empty-url libs`() {
        val libs = realProfile().libraries
        assertTrue(libs.any { it.coord.groupArtifact == "org.lwjgl:lwjgl-opengl" && it.coord.nativeClassifier == null }, "LWJGL3 base module present")
        assertTrue(libs.any { it.coord.groupArtifact == "net.minecraftforge:forge" }, "Forge 1.7.10 present")
        assertTrue(libs.any { it.coord.groupArtifact == "com.github.GTNewHorizons:lwjgl3ify" }, "RFB-bearing forgePatches jar present")
        assertTrue(libs.none { it.coord.nativeClassifier != null }, "no natives on -cp")
        assertTrue(libs.none { it.coord.group == "org.lwjgl.lwjgl" }, "no LWJGL2 on -cp")
        // the empty-url platform-native entries were skipped, not carried with a bad
        // url; the url-bearing base libs (jinput, twitch) stay on -cp as normal.
        assertTrue(libs.none { it.coord.groupArtifact == "net.java.jinput:jinput-platform" }, "empty-url jinput-platform skipped")
        assertTrue(libs.none { it.coord.groupArtifact == "tv.twitch:twitch-platform" }, "empty-url twitch-platform skipped")
        assertTrue(libs.none { it.coord.groupArtifact == "tv.twitch:twitch-external-platform" }, "empty-url twitch-external-platform skipped")
        assertTrue(libs.any { it.coord.groupArtifact == "net.java.jinput:jinput" }, "url-bearing base jinput kept")
    }

    @Test
    fun `registry resolves the lwjgl3ify loader id case-insensitively`() {
        val registry = LoaderRegistry(listOf(resolver))
        assertSame(resolver, registry.resolverFor("lwjgl3ify"))
        assertSame(resolver, registry.resolverFor("Lwjgl3ify"))
        assertNull(registry.resolverFor("forge"))
    }

    @Test
    fun `resolve fails with a message naming the loader and version`() = runTest {
        val net = Lwjgl3ifyResolver(
            clientProvider = HttpClientProvider { HttpClient(MockEngine { respond("nope", HttpStatusCode.NotFound) }) },
            json = json,
            osName = "Linux",
            releaseBase = "https://l3.invalid/dl",
        )
        val ex = assertFailsWith<IOException> { net.resolve("1.7.10", "9.9.9") }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("lwjgl3ify"), "names the loader: $msg")
        assertTrue(msg.contains("9.9.9"), "names the version: $msg")
    }
}
