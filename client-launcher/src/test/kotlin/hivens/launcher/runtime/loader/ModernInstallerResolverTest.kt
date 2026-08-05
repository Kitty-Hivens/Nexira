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
    fun `neoforgeVersionPrefix maps Minecraft to the NeoForge version-index prefix`() {
        assertEquals("21.1.", ModernInstallerResolver.neoforgeVersionPrefix("1.21.1"))
        assertEquals("21.0.", ModernInstallerResolver.neoforgeVersionPrefix("1.21")) // no patch -> .0
        assertEquals("20.4.", ModernInstallerResolver.neoforgeVersionPrefix("1.20.4"))
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
