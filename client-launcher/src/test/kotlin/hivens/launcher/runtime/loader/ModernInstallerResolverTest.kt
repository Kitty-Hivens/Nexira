package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.launcher.runtime.MojangArtifact
import hivens.launcher.runtime.MojangLibrary
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
        json = Json { ignoreUnknownKeys = true },
        javaManager = object : IJavaManager {
            override suspend fun getJavaPath(version: String): Path = Path.of("/bin/java")
        },
        cacheDir = Files.createTempDirectory("modern-cache"),
        loaderId = "neoforge",
    ) { _, version -> "https://example/neoforge-$version-installer.jar" }

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
    fun `locateVersionJson returns the single produced version json`() {
        val staging = Files.createTempDirectory("modern-locate")
        try {
            val versionJson = staging.resolve("versions/neoforge-21.1.66/neoforge-21.1.66.json")
            Files.createDirectories(versionJson.parent)
            Files.writeString(versionJson, "{}")

            assertEquals(versionJson, resolver().locateVersionJson(staging))
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
}
