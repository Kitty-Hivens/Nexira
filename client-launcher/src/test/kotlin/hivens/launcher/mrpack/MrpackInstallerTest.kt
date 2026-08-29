package hivens.launcher.mrpack

import hivens.test.testTransferEngine
import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.update.PackFileRecord
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        override fun observe(): StateFlow<List<PackInstance>> = MutableStateFlow(stored)
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
        return MrpackInstaller(testTransferEngine(dead), json, fakeJava, provisioner, FakeRepository(), tempDir("data"))
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
        val installer = MrpackInstaller(testTransferEngine(provider), json, fakeJava, provisioner, repo, dataDir)

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
        val installer = MrpackInstaller(testTransferEngine(provider), json, fakeJava, provisioner, FakeRepository(), tempDir("data"))

        val instance = installer.install(
            buildMrpack(),
            source = MrpackSource(PackOrigin.Modrinth, id = "AABBCCDD", version = "1.5.0"),
        )

        assertEquals(PackOrigin.Modrinth, instance.packRef.origin, "Modrinth install is tracked, not Local")
        assertEquals("AABBCCDD", instance.packRef.id, "pack id is the Modrinth project id")
        assertEquals("1.5.0", instance.packRef.version)
        assertEquals("1.5.0", instance.pinnedPackVersion, "pinned to the installed Modrinth version")
    }


    @Test
    fun `install records what the pack placed, and only that`() = runTest {
        val provider = HttpClientProvider { HttpClient(engine()) }
        val dataDir = tempDir("data")
        val provisioner = RuntimeProvisioner(
            librariesDir = tempDir("libs"), assetsDir = tempDir("assets"), clientProvider = provider,
            transfers = testTransferEngine(provider), json = json,
            loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MANIFEST_URL, resourcesBaseUrl = RES_BASE,
        )
        val installer = MrpackInstaller(testTransferEngine(provider), json, fakeJava, provisioner, FakeRepository(), dataDir)

        val instance = installer.install(buildMrpack())
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        val record = PackFileRecord.read(clientDir)

        assertEquals(
            setOf("mods/cool.jar", "config/foo.txt", "options.txt"),
            record.keys,
            "the record is exactly what landed: not the skipped client-unsupported file, " +
                "not the server override, and not the record itself",
        )
        assertEquals(sha1(modBytes), record.getValue("mods/cool.jar").sha1, "the index's own hash is kept")
        assertNull(record.getValue("mods/cool.jar").crc32, "a file fetched by URL has no archive entry")
        assertNotNull(record.getValue("config/foo.txt").crc32, "an override carries the archive's CRC")
        assertNotNull(record.getValue("options.txt").crc32, "a client override carries it too")
        assertEquals(modBytes.size.toLong(), record.getValue("mods/cool.jar").size)
    }


    // -- update ---------------------------------------------------------------

    private val modV2Bytes = "COOL-MOD-V2".toByteArray()
    private val stableBytes = "NEVER-CHANGES".toByteArray()
    // A mod whose FILENAME carries its version, which is how packs actually ship
    // them: the update has to retire the old file, not just add the new one.
    private val libV1Bytes = "LIB-1".toByteArray()
    private val libV2Bytes = "LIB-2".toByteArray()

    /**
     * Requests the mock actually served, so a test can assert what was NOT
     * fetched. Concurrent because fetchAll downloads in parallel: a plain
     * ArrayList here threw from inside the mock engine under load.
     */
    private val served = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun updateEngine() = MockEngine { req ->
        val jsonH = headersOf(HttpHeaders.ContentType, "application/json")
        val emptyIndex = """{"objects":{}}"""
        val versionJson = """
            {"assetIndex":{"id":"8","sha1":"${sha1(emptyIndex.toByteArray())}","size":${emptyIndex.length},"url":"$ASSET_INDEX_URL"},
             "downloads":{"client":{"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
             "libraries":[]}
        """.trimIndent()
        val url = req.url.toString()
        served += url
        when (url) {
            MANIFEST_URL -> respond("""{"versions":[{"id":"1.20.1","url":"$VERSION_URL"}]}""", HttpStatusCode.OK, jsonH)
            VERSION_URL -> respond(versionJson, HttpStatusCode.OK, jsonH)
            ASSET_INDEX_URL -> respond(emptyIndex, HttpStatusCode.OK, jsonH)
            CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
            MOD_URL -> respond(ByteReadChannel(modBytes), HttpStatusCode.OK)
            MOD_V2_URL -> respond(ByteReadChannel(modV2Bytes), HttpStatusCode.OK)
            STABLE_URL -> respond(ByteReadChannel(stableBytes), HttpStatusCode.OK)
            LIB_V1_URL -> respond(ByteReadChannel(libV1Bytes), HttpStatusCode.OK)
            LIB_V2_URL -> respond(ByteReadChannel(libV2Bytes), HttpStatusCode.OK)
            else -> respond("missing $url", HttpStatusCode.NotFound)
        }
    }

    /** Builds a pack whose contents differ between versions, so an update has work to do. */
    private fun buildVersionedPack(second: Boolean): Path {
        val file = Files.createTempFile("test-v", ".mrpack").also { tempDirs.add(it) }
        val cool = if (second) modV2Bytes to MOD_V2_URL else modBytes to MOD_URL
        val lib = if (second) Triple("mods/lib-2.0.jar", libV2Bytes, LIB_V2_URL) else Triple("mods/lib-1.0.jar", libV1Bytes, LIB_V1_URL)
        val index = """
            {"formatVersion":1,"game":"minecraft","versionId":"${if (second) "2.0.0" else "1.0.0"}","name":"Test Pack",
             "dependencies":{"minecraft":"1.20.1"},
             "files":[
               {"path":"mods/cool.jar","hashes":{"sha1":"${sha1(cool.first)}"},"downloads":["${cool.second}"],"fileSize":${cool.first.size}},
               {"path":"mods/stable.jar","hashes":{"sha1":"${sha1(stableBytes)}"},"downloads":["$STABLE_URL"],"fileSize":${stableBytes.size}},
               {"path":"${lib.first}","hashes":{"sha1":"${sha1(lib.second)}"},"downloads":["${lib.third}"],"fileSize":${lib.second.size}}
             ]}
        """.trimIndent()
        ZipOutputStream(Files.newOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("modrinth.index.json")); zos.write(index.toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("overrides/config/foo.txt")); zos.write((if (second) "FOO-V2" else "FOO").toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("overrides/config/keep.txt")); zos.write("KEEP".toByteArray()); zos.closeEntry()
            if (!second) {
                zos.putNextEntry(ZipEntry("overrides/config/gone.txt")); zos.write("GONE".toByteArray()); zos.closeEntry()
            }
        }
        return file
    }

    private fun updatableInstaller(dataDir: Path): MrpackInstaller {
        val provider = HttpClientProvider { HttpClient(updateEngine()) }
        val provisioner = RuntimeProvisioner(
            librariesDir = tempDir("libs"), assetsDir = tempDir("assets"), clientProvider = provider,
            transfers = testTransferEngine(provider), json = json,
            loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MANIFEST_URL, resourcesBaseUrl = RES_BASE,
        )
        return MrpackInstaller(testTransferEngine(provider), json, fakeJava, provisioner, FakeRepository(), dataDir)
    }

    @Test
    fun `update moves the pack forward without touching what the player added`() = runTest {
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val instance = installer.install(buildVersionedPack(second = false))
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)

        // The player makes the instance theirs.
        Files.writeString(clientDir.resolve("mods/mine.jar"), "MY-MOD")
        Files.createDirectories(clientDir.resolve("saves/world"))
        Files.writeString(clientDir.resolve("saves/world/level.dat"), "WORLD")

        served.clear()
        installer.update(instance, buildVersionedPack(second = true))

        assertEquals("MY-MOD", clientDir.resolve("mods/mine.jar").readText(), "the player's mod survives")
        assertEquals("WORLD", clientDir.resolve("saves/world/level.dat").readText(), "the player's world survives")
        assertEquals("COOL-MOD-V2", clientDir.resolve("mods/cool.jar").readText(), "the changed mod is fetched")
        assertEquals("FOO-V2", clientDir.resolve("config/foo.txt").readText(), "the changed override is written")
        assertFalse(Files.exists(clientDir.resolve("config/gone.txt")), "what this version stopped shipping is retired")
    }

    @Test
    fun `update does not re-fetch a mod the new version did not change`() = runTest {
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val instance = installer.install(buildVersionedPack(second = false))

        served.clear()
        installer.update(instance, buildVersionedPack(second = true))

        assertTrue(MOD_V2_URL in served, "the changed mod is fetched")
        assertFalse(STABLE_URL in served, "the unchanged mod is not fetched again -- this is the whole point")
    }

    @Test
    fun `an override this version leaves alone keeps the player's edit`() = runTest {
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val instance = installer.install(buildVersionedPack(second = false))
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)

        Files.writeString(clientDir.resolve("config/keep.txt"), "PLAYER-TUNED")
        Files.writeString(clientDir.resolve("config/foo.txt"), "PLAYER-ALSO-TUNED-THIS")

        installer.update(instance, buildVersionedPack(second = true))

        assertEquals("PLAYER-TUNED", clientDir.resolve("config/keep.txt").readText(),
            "the pack did not move this file, so neither do we")
        assertEquals("FOO-V2", clientDir.resolve("config/foo.txt").readText(),
            "the pack did move this one, so the pack wins and the log says so")
    }

    @Test
    fun `the new record holds the pack's files and not the player's`() = runTest {
        // The trap: taking stock by walking the directory is right at install and
        // wrong here. Recording the player's mod would claim it as the pack's,
        // and the update after this one would retire it.
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val instance = installer.install(buildVersionedPack(second = false))
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        Files.writeString(clientDir.resolve("mods/mine.jar"), "MY-MOD")

        installer.update(instance, buildVersionedPack(second = true))

        assertEquals(
            setOf("mods/cool.jar", "mods/stable.jar", "mods/lib-2.0.jar", "config/foo.txt", "config/keep.txt"),
            PackFileRecord.read(clientDir).keys,
        )
    }

    @Test
    fun `an instance with no record deletes nothing`() = runTest {
        // Instances installed before records existed. Not knowing what was ours,
        // the safe reading is that none of it was.
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val instance = installer.install(buildVersionedPack(second = false))
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        Files.delete(clientDir.resolve(PackFileRecord.FILE_NAME))

        installer.update(instance, buildVersionedPack(second = true))

        assertTrue(Files.exists(clientDir.resolve("config/gone.txt")),
            "with no record we cannot claim this file, so it stays")
        assertEquals("FOO-V2", clientDir.resolve("config/foo.txt").readText(), "content is still brought up to date")
    }

    @Test
    fun `update pins the instance to the new version`() = runTest {
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val instance = installer.install(buildVersionedPack(second = false))

        val updated = installer.update(
            instance,
            buildVersionedPack(second = true),
            source = MrpackSource(PackOrigin.Modrinth, id = "AABBCCDD", version = "2.0.0"),
        )

        assertEquals("2.0.0", updated.pinnedPackVersion)
        assertEquals("2.0.0", updated.packRef.version)
    }


    @Test
    fun `an instance with no record retires the old files once given the version it has`() = runTest {
        // The shape that reached a real launcher: instances installed before
        // records existed updated without retiring anything, so a mod whose
        // filename carries its version ended up on disk twice and the game
        // would not start. The installed version's own archive is the baseline.
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val v1 = buildVersionedPack(second = false)
        val instance = installer.install(v1)
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        Files.delete(clientDir.resolve(PackFileRecord.FILE_NAME))

        installer.update(instance, buildVersionedPack(second = true), installedArchive = v1)

        assertTrue(Files.exists(clientDir.resolve("mods/lib-2.0.jar")), "the new file arrived")
        assertFalse(Files.exists(clientDir.resolve("mods/lib-1.0.jar")), "the old file was retired, not left beside it")
    }

    @Test
    fun `without a baseline the old file survives, which is why one is fetched`() = runTest {
        val dataDir = tempDir("data")
        val installer = updatableInstaller(dataDir)
        val instance = installer.install(buildVersionedPack(second = false))
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        Files.delete(clientDir.resolve(PackFileRecord.FILE_NAME))

        installer.update(instance, buildVersionedPack(second = true))

        assertTrue(Files.exists(clientDir.resolve("mods/lib-1.0.jar")), "nothing knew this file was the pack's")
    }

    private companion object {
        const val MANIFEST_URL = "https://piston-meta.test/manifest.json"
        const val VERSION_URL = "https://piston-meta.test/1.20.1.json"
        const val ASSET_INDEX_URL = "https://piston-meta.test/assets/8.json"
        const val CLIENT_URL = "https://piston-data.test/client.jar"
        const val RES_BASE = "https://resources.test"
        const val MOD_URL = "https://cdn.test/cool.jar"
        const val BAD_URL = "https://cdn.test/nope.jar"
        const val MOD_V2_URL = "https://cdn.test/cool-v2.jar"
        const val STABLE_URL = "https://cdn.test/stable.jar"
        const val LIB_V1_URL = "https://cdn.test/lib-1.0.jar"
        const val LIB_V2_URL = "https://cdn.test/lib-2.0.jar"
    }
}
