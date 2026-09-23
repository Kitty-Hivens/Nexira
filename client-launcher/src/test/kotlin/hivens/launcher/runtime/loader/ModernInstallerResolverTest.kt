package hivens.launcher.runtime.loader

import hivens.test.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.launcher.runtime.MojangArtifact
import hivens.launcher.runtime.MojangLibrary
import hivens.launcher.runtime.MojangOs
import hivens.launcher.runtime.MojangRule
import hivens.launcher.runtime.libraryRulesAllow
import hivens.launcher.runtime.MojangLibraryDownloads
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS as JunitOs
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModernInstallerResolverTest {

    private fun resolver() = ModernInstallerResolver(
        clientProvider = HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) },
        transfers = testTransferEngine(HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) }),
        json = Json { ignoreUnknownKeys = true },
        javaManager = object : IJavaManager {
            override suspend fun getJavaPath(version: String): Path = Path.of("/bin/java")
            override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = Path.of("/bin/java")
        },
        cacheDir = Files.createTempDirectory("modern-cache"),
        loaderId = "neoforge",
        latestVersion = { "21.1.0" }, // unused by the harvest / place-only / locate tests
    ) { _, version -> "https://example/neoforge-$version-installer.jar" }

    /** Same shape as [resolver], under the `forge` id -- its overlay dir name is `<mc>-forge-<build>`. */
    private fun forgeResolver() = ModernInstallerResolver(
        clientProvider = HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) },
        transfers = testTransferEngine(HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) }),
        json = Json { ignoreUnknownKeys = true },
        javaManager = object : IJavaManager {
            override suspend fun getJavaPath(version: String): Path = Path.of("/bin/java")
            override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = Path.of("/bin/java")
        },
        cacheDir = Files.createTempDirectory("modern-cache-forge"),
        loaderId = "forge",
        latestVersion = { "47.4.10" },
    ) { mc, version -> "https://example/forge-$mc-$version-installer.jar" }

    @Test
    fun `harvest maps a produced library to a localFile copy spec`() {
        val staging = Files.createTempDirectory("modern-harvest")
        try {
            val rel = "net/neoforged/neoforge/21.1.66/neoforge-21.1.66.jar"
            val file = staging.resolve("libraries").resolve(rel)
            Files.createDirectories(file.parent)
            Files.writeString(file, "JAR")
            val lib = MojangLibrary(
                name = "net.neoforged:neoforge:21.1.66",
                downloads = MojangLibraryDownloads(MojangArtifact(path = rel, sha1 = "abc", size = 3, url = "")),
            )

            val spec = resolver().harvest(lib, staging)

            assertEquals(file, spec.localFile)
            assertEquals("abc", spec.sha1)
            assertEquals(3, spec.size)
            assertNull(spec.url, "localFile spec carries no url")
            assertNull(spec.bundled, "localFile spec carries no bytes")
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `harvest derives the path from the coordinate when downloads are absent`() {
        val staging = Files.createTempDirectory("modern-harvest-coord")
        try {
            val file = staging.resolve("libraries").resolve("net/foo/bar/1.0/bar-1.0.jar")
            Files.createDirectories(file.parent)
            Files.writeString(file, "X")

            val spec = resolver().harvest(MojangLibrary(name = "net.foo:bar:1.0"), staging)

            assertEquals(file, spec.localFile)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `harvest fails when the installer produced no such library`() {
        val staging = Files.createTempDirectory("modern-harvest-missing")
        try {
            assertFailsWith<IOException> {
                resolver().harvest(MojangLibrary(name = "net.absent:lib:9.9"), staging)
            }
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a library ruled out for this host is not harvested`() {
        // The failure this pins: a NeoForge version json lists ca.weblite:java-objc-bridge
        // behind an osx rule, the installer rightly does not produce it on Windows or
        // Linux, and harvesting it anyway failed the whole pack install.
        val macOnly = MojangLibrary(
            name = "ca.weblite:java-objc-bridge:1.1",
            rules = listOf(MojangRule("allow", MojangOs("osx"))),
        )
        val everywhere = MojangLibrary(name = "net.foo:bar:1.0")

        assertEquals(
            listOf(everywhere),
            listOf(everywhere, macOnly).filter { libraryRulesAllow(it.rules, "windows") },
            "a mac-only library must be dropped before harvest on windows",
        )
        assertEquals(
            listOf(everywhere, macOnly),
            listOf(everywhere, macOnly).filter { libraryRulesAllow(it.rules, "osx") },
            "and kept on the host it was meant for",
        )
    }

    @Test
    fun `locateVersionJson returns the single produced version json`() {
        val staging = Files.createTempDirectory("modern-locate")
        try {
            val versionJson = staging.resolve("versions/neoforge-21.1.66/neoforge-21.1.66.json")
            Files.createDirectories(versionJson.parent)
            Files.writeString(versionJson, "{}")

            assertEquals(versionJson, resolver().locateVersionJson(staging, "1.21.1"))
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `locateVersionJson skips the vanilla json the installer downloads beside the loader's`() {
        val staging = Files.createTempDirectory("modern-locate-two")
        try {
            // What a real --installClient target holds. The vanilla dir sorts first
            // by name, which is what a name-ordered filesystem enumerates first.
            val vanilla = staging.resolve("versions/1.21.1/1.21.1.json")
            val loader = staging.resolve("versions/neoforge-21.1.186/neoforge-21.1.186.json")
            Files.createDirectories(vanilla.parent); Files.writeString(vanilla, "{}")
            Files.createDirectories(loader.parent); Files.writeString(loader, "{}")

            assertEquals(loader, resolver().locateVersionJson(staging, "1.21.1"))
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `locateVersionJson picks the loader entry for Forge's mc-prefixed dir name`() {
        val staging = Files.createTempDirectory("modern-locate-forge")
        try {
            // Forge names its overlay `<mc>-forge-<build>`, so the vanilla dir is a
            // prefix of it -- the choice cannot rest on "does not start with mc".
            val vanilla = staging.resolve("versions/1.20.1/1.20.1.json")
            val loader = staging.resolve("versions/1.20.1-forge-47.4.10/1.20.1-forge-47.4.10.json")
            Files.createDirectories(vanilla.parent); Files.writeString(vanilla, "{}")
            Files.createDirectories(loader.parent); Files.writeString(loader, "{}")

            assertEquals(loader, forgeResolver().locateVersionJson(staging, "1.20.1"))
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `locateVersionJson is deterministic when nothing names the loader`() {
        val staging = Files.createTempDirectory("modern-locate-odd")
        try {
            val vanilla = staging.resolve("versions/1.21.1/1.21.1.json")
            val other = staging.resolve("versions/zzz-overlay/zzz-overlay.json")
            Files.createDirectories(vanilla.parent); Files.writeString(vanilla, "{}")
            Files.createDirectories(other.parent); Files.writeString(other, "{}")

            // Falls through to "not the mc version", never to directory-stream order.
            assertEquals(other, resolver().locateVersionJson(staging, "1.21.1"))
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @Test
    fun `collectPlaceOnly returns every jar under the cache libraries tree`() {
        val staging = Files.createTempDirectory("modern-placeonly")
        try {
            val srg = staging.resolve("libraries/net/minecraft/client/1.21.1-x/client-1.21.1-x-srg.jar")
            val universal = staging.resolve("libraries/net/neoforged/neoforge/21.1.232/neoforge-21.1.232-universal.jar")
            Files.createDirectories(srg.parent); Files.writeString(srg, "A")
            Files.createDirectories(universal.parent); Files.writeString(universal, "B")

            val rels = resolver().collectPlaceOnly(staging).map { it.relPath }.toSet()

            assertEquals(2, rels.size)
            assertTrue(rels.contains("net/minecraft/client/1.21.1-x/client-1.21.1-x-srg.jar"))
            assertTrue(rels.contains("net/neoforged/neoforge/21.1.232/neoforge-21.1.232-universal.jar"))
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    /**
     * The installer used to be waited on with a plain `waitFor` of up to twenty
     * minutes that no cancellation reached, so a stopped launch left it writing into
     * a cache the next launch then deleted. A shell script stands in for the JVM: it
     * marks that it started, sleeps, and marks that it finished, which it must not
     * get to do.
     */
    @Test
    @EnabledOnOs(JunitOs.LINUX, disabledReason = "the stand-in installer is a shell script")
    fun `a cancelled launch kills the installer instead of waiting for it`() = runBlocking {
        val cache = Files.createTempDirectory("modern-cancel")
        val fakeJava = cache.resolve("java").also {
            Files.writeString(it, "#!/bin/sh\ntouch started\nsleep 30\ntouch finished\n")
            it.toFile().setExecutable(true)
        }
        val engine = MockEngine { respond(ByteReadChannel("JAR".toByteArray()), HttpStatusCode.OK) }
        val resolver = ModernInstallerResolver(
            clientProvider = HttpClientProvider { HttpClient(engine) },
            transfers = testTransferEngine(HttpClientProvider { HttpClient(engine) }),
            json = Json { ignoreUnknownKeys = true },
            javaManager = object : IJavaManager {
                override suspend fun getJavaPath(version: String): Path = fakeJava
                override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = fakeJava
            },
            cacheDir = cache,
            loaderId = "neoforge",
            latestVersion = { "21.1.0" },
        ) { _, version -> "https://example.test/neoforge-$version-installer.jar" }
        val target = cache.resolve("neoforge-1.21.1-21.1.0")
        try {
            val job = launch(Dispatchers.IO) { resolver.resolve("1.21.1", "21.1.0") }
            withTimeout(15_000) { while (!Files.exists(target.resolve("started"))) delay(50) }

            job.cancel()
            withTimeout(10_000) { job.join() }
            delay(1_500)

            assertFalse(Files.exists(target.resolve("finished")), "the installer outlived the launch that started it")
        } finally {
            cache.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ForgeResolver splits launchwrapper era at Minecraft 1_12`() {
        assertTrue(ForgeResolver.isLaunchwrapperEra("1.12.2"))
        assertTrue(ForgeResolver.isLaunchwrapperEra("1.7.10"))
        assertFalse(ForgeResolver.isLaunchwrapperEra("1.13.2"))
        assertFalse(ForgeResolver.isLaunchwrapperEra("1.16.5"))
        assertFalse(ForgeResolver.isLaunchwrapperEra("1.20.1"))
        assertFalse(ForgeResolver.isLaunchwrapperEra("nonsense"))
    }

    @Test
    fun `neoforgeLine maps each Minecraft era to NeoForge's artifact and prefix`() {
        assertEquals(NeoForgeLine("neoforge", "21.1."), ModernInstallerResolver.neoforgeLine("1.21.1"))
        assertEquals(NeoForgeLine("neoforge", "21.0."), ModernInstallerResolver.neoforgeLine("1.21")) // no patch -> .0
        assertEquals(NeoForgeLine("neoforge", "20.4."), ModernInstallerResolver.neoforgeLine("1.20.4"))
        // Published under Forge's coordinates, with the Minecraft version in front.
        assertEquals(NeoForgeLine("forge", "1.20.1-"), ModernInstallerResolver.neoforgeLine("1.20.1"))
        // The year-numbered releases keep every part.
        assertEquals(NeoForgeLine("neoforge", "26.3.0."), ModernInstallerResolver.neoforgeLine("26.3"))
        assertEquals(NeoForgeLine("neoforge", "26.1.2."), ModernInstallerResolver.neoforgeLine("26.1.2"))
    }

    @Test
    fun `pickNeoForge takes the newest release, else the newest beta, never a snapshot`() {
        val index = listOf(
            "21.1.250", "26.1.0.0-alpha.1+snapshot-1", "26.1.0.19-beta",
            "26.2.0.87-beta", "26.2.0.86", "26.2.0.88",
            "26.3.0.15-beta", "26.3.0.16-beta",
        )
        assertEquals("26.2.0.88", ModernInstallerResolver.pickNeoForge(index, NeoForgeLine("neoforge", "26.2.0.")))
        assertEquals("26.3.0.16-beta", ModernInstallerResolver.pickNeoForge(index, NeoForgeLine("neoforge", "26.3.0.")),
            "a release NeoForge only has betas for is still installable")
        assertEquals("26.1.0.19-beta", ModernInstallerResolver.pickNeoForge(index, NeoForgeLine("neoforge", "26.1.0.")))
        assertNull(ModernInstallerResolver.pickNeoForge(index, NeoForgeLine("neoforge", "27.1.0.")))
    }

    @Test
    fun `the 1_20_1 line reads the forge artifact and accepts a version typed without its prefix`() {
        val line = ModernInstallerResolver.neoforgeLine("1.20.1")
        val index = listOf("1.20.1-47.1.105", "1.20.1-47.1.106", "47.1.82")
        assertEquals("1.20.1-47.1.106", ModernInstallerResolver.pickNeoForge(index, line), "the stray unprefixed entry is not this line")
        assertEquals("1.20.1-47.1.106", ModernInstallerResolver.neoforgeCoordinate(line, "47.1.106"))
        assertEquals("1.20.1-47.1.106", ModernInstallerResolver.neoforgeCoordinate(line, "1.20.1-47.1.106"))
    }

    @Test
    fun `a Forge version typed with its Minecraft prefix is not prefixed twice`() {
        assertEquals("47.2.0", ModernInstallerResolver.forgeBuild("1.20.1", "1.20.1-47.2.0"))
        assertEquals("47.2.0", ModernInstallerResolver.forgeBuild("1.20.1", "47.2.0"))
    }

    @Test
    fun `pickForgePromotion prefers recommended, falls back to latest, else null`() {
        val json = Json { ignoreUnknownKeys = true }
        val both = """{"promos":{"1.21.1-latest":"52.1.15","1.21.1-recommended":"52.1.0"}}"""
        assertEquals("52.1.0", ModernInstallerResolver.pickForgePromotion(json, both, "1.21.1"))
        val latestOnly = """{"promos":{"1.21.1-latest":"52.1.15"}}"""
        assertEquals("52.1.15", ModernInstallerResolver.pickForgePromotion(json, latestOnly, "1.21.1"))
        assertNull(ModernInstallerResolver.pickForgePromotion(json, """{"promos":{}}""", "1.21.1"))
    }
}
