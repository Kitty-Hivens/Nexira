package hivens.launcher.mrpack

import hivens.launcher.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderRegistry
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MrpackInstallerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tempDirs = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { tempDirs.add(it) }

    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private class FakeRepository : IPackRepository {
        val stored = mutableListOf<PackInstance>()
        override fun observe(): Flow<List<PackInstance>> = flowOf(stored)
        override suspend fun list(): List<PackInstance> = stored
        override suspend fun get(id: String): PackInstance? = stored.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) { stored.add(instance) }
        override suspend fun delete(id: String) { stored.removeAll { it.id == id } }
    }

    private val fakeJava = object : IJavaManager {
        override suspend fun getJavaPath(version: String): Path = Path.of("/bin/java")
        override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = Path.of("/bin/java")
    }

    // -- pure helpers ---------------------------------------------------------

    private fun bareInstaller(): MrpackInstaller {
        val dead = HttpClientProvider { HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) }
        val provisioner = RuntimeProvisioner(tempDir("libs"), tempDir("assets"), dead, testTransferEngine(dead), json)
        return MrpackInstaller(dead, json, fakeJava, provisioner, FakeRepository(), tempDir("data"))
    }

    @Test
    fun `resolveLoader maps modrinth dependency keys to registry ids`() {
        val it = bareInstaller()
        assertEquals("neoforge" to "21.1.1", it.resolveLoader(mapOf("minecraft" to "1.21.1", "neoforge" to "21.1.1")))
        assertEquals("forge" to "47.2.0", it.resolveLoader(mapOf("forge" to "47.2.0")))
        assertEquals("fabric" to "0.16.0", it.resolveLoader(mapOf("fabric-loader" to "0.16.0")))
        assertEquals("quilt" to "0.26.0", it.resolveLoader(mapOf("quilt-loader" to "0.26.0")))
        assertEquals(null to "", it.resolveLoader(mapOf("minecraft" to "1.20.1")))
    }

    @Test
    fun `safeResolve rejects path traversal`() {
        val installer = bareInstaller()
        val base = tempDir("base")
        assertFailsWith<SecurityException> { installer.safeResolve(base, "../escape.txt") }
        assertFailsWith<SecurityException> { installer.safeResolve(base, "mods/../../escape.txt") }
        assertTrue(installer.safeResolve(base, "mods/ok.jar").startsWith(base))
    }

    // -- offline end-to-end install -------------------------------------------

    private val modBytes = "COOL-MOD".toByteArray()
    private val clientBytes = "VANILLA-CLIENT".toByteArray()

    private fun buildMrpack(): Path {
        val file = Files.createTempFile("test", ".mrpack").also { tempDirs.add(it) }
        val index = """
            {"formatVersion":1,"game":"minecraft","versionId":"1.0.0","name":"Test Pack",
             "dependencies":{"minecraft":"1.20.1"},
             "files":[
               {"path":"mods/cool.jar","hashes":{"sha1":"${sha1(modBytes)}"},"downloads":["$MOD_URL"],"fileSize":${modBytes.size}},
               {"path":"mods/server-only.jar","hashes":{"sha1":"deadbeef"},"env":{"client":"unsupported","server":"required"},"downloads":["$BAD_URL"]}
             ]}
        """.trimIndent()
        ZipOutputStream(Files.newOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("modrinth.index.json")); zos.write(index.toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("overrides/config/foo.txt")); zos.write("FOO".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("client-overrides/options.txt")); zos.write("OPT".toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("server-overrides/server.properties")); zos.write("SRV".toByteArray()); zos.closeEntry()
        }
        return file
    }

    private fun engine() = MockEngine { req ->
        val jsonH = headersOf(HttpHeaders.ContentType, "application/json")
        val emptyIndex = """{"objects":{}}"""
        val versionJson = """
            {"assetIndex":{"id":"8","sha1":"${sha1(emptyIndex.toByteArray())}","size":${emptyIndex.length},"url":"$ASSET_INDEX_URL"},
             "downloads":{"client":{"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
             "libraries":[]}
        """.trimIndent()
        when (req.url.toString()) {
            MANIFEST_URL -> respond("""{"versions":[{"id":"1.20.1","url":"$VERSION_URL"}]}""", HttpStatusCode.OK, jsonH)
            VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonH)
            ASSET_INDEX_URL -> respond(emptyIndex, HttpStatusCode.OK, jsonH)
            CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
            MOD_URL -> respond(ByteReadChannel(modBytes), HttpStatusCode.OK)
            else -> respond("missing ${req.url}", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun `install downloads files, copies overrides, skips unsupported, registers instance`() = runTest {
        val provider = HttpClientProvider { HttpClient(engine()) }
        val libs = tempDir("libs")
        val assets = tempDir("assets")
        val dataDir = tempDir("data")
        val provisioner = RuntimeProvisioner(
            librariesDir = libs, assetsDir = assets, clientProvider = provider,
            transfers = testTransferEngine(provider), json = json,
            loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MANIFEST_URL, resourcesBaseUrl = RES_BASE,
        )
        val repo = FakeRepository()
        val installer = MrpackInstaller(provider, json, fakeJava, provisioner, repo, dataDir)

        val instance = installer.install(buildMrpack())

        assertEquals(PackOrigin.Local, instance.packRef.origin)
        assertEquals("Test Pack", instance.displayName)
        assertEquals("1.20.1", instance.cachedManifest?.minecraftVersion)
        assertEquals("vanilla", instance.cachedManifest?.loaderName)
        assertTrue(repo.stored.any { it.id == instance.id }, "instance registered")

        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        assertEquals("COOL-MOD", clientDir.resolve("mods/cool.jar").readText())
        assertFalse(Files.exists(clientDir.resolve("mods/server-only.jar")), "client-unsupported file skipped")
        assertEquals("FOO", clientDir.resolve("config/foo.txt").readText())
        assertEquals("OPT", clientDir.resolve("options.txt").readText())
        assertFalse(Files.exists(clientDir.resolve("server.properties")), "server-overrides not copied to a client instance")
        assertTrue(Files.exists(libs.resolve("net/minecraft/minecraft/1.20.1/minecraft-1.20.1.jar")), "vanilla client provisioned")
    }

    @Test
    fun `install stamps Modrinth origin, project id and version from the source`() = runTest {
        val provider = HttpClientProvider { HttpClient(engine()) }
        val provisioner = RuntimeProvisioner(
            librariesDir = tempDir("libs"), assetsDir = tempDir("assets"), clientProvider = provider,
            transfers = testTransferEngine(provider), json = json,
            loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MANIFEST_URL, resourcesBaseUrl = RES_BASE,
        )
        val installer = MrpackInstaller(provider, json, fakeJava, provisioner, FakeRepository(), tempDir("data"))

        val instance = installer.install(
            buildMrpack(),
            source = MrpackSource(PackOrigin.Modrinth, id = "AABBCCDD", version = "1.5.0"),
        )

        assertEquals(PackOrigin.Modrinth, instance.packRef.origin, "Modrinth install is tracked, not Local")
        assertEquals("AABBCCDD", instance.packRef.id, "pack id is the Modrinth project id")
        assertEquals("1.5.0", instance.packRef.version)
        assertEquals("1.5.0", instance.pinnedPackVersion, "pinned to the installed Modrinth version")
    }

    private companion object {
        const val MANIFEST_URL = "https://piston-meta.test/manifest.json"
        const val VERSION_URL = "https://piston-meta.test/1.20.1.json"
        const val ASSET_INDEX_URL = "https://piston-meta.test/assets/8.json"
        const val CLIENT_URL = "https://piston-data.test/client.jar"
        const val RES_BASE = "https://resources.test"
        const val MOD_URL = "https://cdn.test/cool.jar"
        const val BAD_URL = "https://cdn.test/nope.jar"
    }
}
