package hivens.launcher.update

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.update.UpdateOutcome
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.mrpack.MrpackSource
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Modrinth update path end to end, over a mock Modrinth and a mock CDN.
 *
 * It had no test of its own. The mirror path's failure test covered the one
 * behind the same interface, and this one had no journal, no rollback and no
 * prune to cover.
 */
class ModrinthPackUpdaterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { temps.add(it) }
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private class FakeRepo : IPackRepository {
        val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = map.values.toList()
        override suspend fun get(id: String): PackInstance? = map[id]
        override suspend fun put(instance: PackInstance) { map[instance.id] = instance }
        override suspend fun delete(id: String) { map.remove(id) }
    }

    private val fakeJava = object : IJavaManager {
        override suspend fun getJavaPath(version: String): Path = Path.of("/bin/java")
        override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = Path.of("/bin/java")
    }

    private val clientBytes = "VANILLA-CLIENT".toByteArray()
    private val coolV1 = "COOL-MOD".toByteArray()
    private val coolV2 = "COOL-MOD-V2".toByteArray()
    private val libV1 = "LIB-1".toByteArray()
    private val libV2 = "LIB-2".toByteArray()

    /** A pack whose second version changes a mod, renames a lib, rewrites one override and drops another. */
    private fun buildPack(second: Boolean, libV2Url: String = LIB_V2_URL): ByteArray {
        val file = Files.createTempFile("pack", ".mrpack").also { temps.add(it) }
        val cool = if (second) coolV2 to COOL_V2_URL else coolV1 to COOL_V1_URL
        val lib = if (second) Triple("mods/lib-2.0.jar", libV2, libV2Url) else Triple("mods/lib-1.0.jar", libV1, LIB_V1_URL)
        val index = """
            {"formatVersion":1,"game":"minecraft","versionId":"${if (second) V2 else V1}","name":"Test Pack",
             "dependencies":{"minecraft":"1.20.1"},
             "files":[
               {"path":"mods/cool.jar","hashes":{"sha1":"${sha1(cool.first)}"},"downloads":["${cool.second}"],"fileSize":${cool.first.size}},
               {"path":"${lib.first}","hashes":{"sha1":"${sha1(lib.second)}"},"downloads":["${lib.third}"],"fileSize":${lib.second.size}}
             ]}
        """.trimIndent()
        ZipOutputStream(Files.newOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("modrinth.index.json")); zos.write(index.toByteArray()); zos.closeEntry()
            zos.putNextEntry(ZipEntry("overrides/config/foo.txt")); zos.write((if (second) "FOO-V2" else "FOO").toByteArray()); zos.closeEntry()
            if (!second) {
                zos.putNextEntry(ZipEntry("overrides/config/gone.txt")); zos.write("GONE".toByteArray()); zos.closeEntry()
            }
        }
        return Files.readAllBytes(file)
    }

    private inner class Harness(breakV2: Boolean = false) {
        val dataDir = tempDir("data")
        val repo = FakeRepo()
        private val packV1 = buildPack(second = false)
        // A broken build names a lib the CDN does not have, so its fetch fails after
        // the changed mod may already have landed.
        private val packV2 = buildPack(second = true, libV2Url = if (breakV2) MISSING_URL else LIB_V2_URL)

        private fun versionJson(id: String, number: String, date: String, url: String, bytes: ByteArray) =
            """{"id":"$id","project_id":"$PROJECT","version_number":"$number","date_published":"$date",
                "game_versions":["1.20.1"],"loaders":[],
                "files":[{"hashes":{"sha1":"${sha1(bytes)}"},"url":"$url","filename":"pack.mrpack","primary":true,"size":${bytes.size}}]}"""

        private val engine = MockEngine { req ->
            val jsonH = headersOf(HttpHeaders.ContentType, "application/json")
            val emptyIndex = """{"objects":{}}"""
            val mcVersion = """
                {"assetIndex":{"id":"8","sha1":"${sha1(emptyIndex.toByteArray())}","size":${emptyIndex.length},"url":"$ASSET_INDEX_URL"},
                 "downloads":{"client":{"sha1":"${sha1(clientBytes)}","size":${clientBytes.size},"url":"$CLIENT_URL"}},
                 "libraries":[]}
            """.trimIndent()
            when (req.url.toString()) {
                VERSIONS_URL -> respond(
                    "[" + versionJson("v2id", V2, "2026-02-02T00:00:00Z", PACK_V2_URL, packV2) + "," +
                        versionJson("v1id", V1, "2026-01-01T00:00:00Z", PACK_V1_URL, packV1) + "]",
                    HttpStatusCode.OK, jsonH,
                )
                PACK_V1_URL -> respond(ByteReadChannel(packV1), HttpStatusCode.OK)
                PACK_V2_URL -> respond(ByteReadChannel(packV2), HttpStatusCode.OK)
                MC_MANIFEST_URL -> respond("""{"versions":[{"id":"1.20.1","url":"$MC_VERSION_URL"}]}""", HttpStatusCode.OK, jsonH)
                MC_VERSION_URL -> respond(mcVersion, HttpStatusCode.OK, jsonH)
                ASSET_INDEX_URL -> respond(emptyIndex, HttpStatusCode.OK, jsonH)
                CLIENT_URL -> respond(ByteReadChannel(clientBytes), HttpStatusCode.OK)
                COOL_V1_URL -> respond(ByteReadChannel(coolV1), HttpStatusCode.OK)
                COOL_V2_URL -> respond(ByteReadChannel(coolV2), HttpStatusCode.OK)
                LIB_V1_URL -> respond(ByteReadChannel(libV1), HttpStatusCode.OK)
                LIB_V2_URL -> respond(ByteReadChannel(libV2), HttpStatusCode.OK)
                else -> respond("missing ${req.url}", HttpStatusCode.NotFound)
            }
        }
        private val provider = HttpClientProvider { HttpClient(engine) }
        private val transfers = testTransferEngine(provider)
        private val provisioner = RuntimeProvisioner(
            librariesDir = tempDir("libs"), assetsDir = tempDir("assets"), clientProvider = provider,
            transfers = transfers, json = json,
            loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MC_MANIFEST_URL, resourcesBaseUrl = RES_BASE,
        )
        private val installer = MrpackInstaller(transfers, json, fakeJava, provisioner, repo, dataDir)
        val snapshots = PackSnapshotService(dataDir, json)
        val journal = ApplyJournal(dataDir, json)
        val updater = ModrinthPackUpdater(ModrinthClient(provider, transfers, json), installer, repo, snapshots, journal, dataDir)

        suspend fun installV1(): PackInstance {
            val archive = tempDir("dl").resolve("v1.mrpack").also { Files.write(it, packV1) }
            return installer.install(archive, MrpackSource(PackOrigin.Modrinth, PROJECT, V1, buildKey = "v1id"))
        }

        fun clientDirOf(instance: PackInstance): Path = dataDir.resolve("instances").resolve(instance.instanceDirName)
    }

    @Test
    fun `an update lands, commits and leaves a snapshot but no marker`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        val dir = h.clientDirOf(instance)

        assertTrue(h.updater.applyUpdate(instance, null, null) is UpdateOutcome.Applied)

        assertEquals("COOL-MOD-V2", dir.resolve("mods/cool.jar").readText())
        assertTrue(Files.exists(dir.resolve("mods/lib-2.0.jar")))
        assertFalse(Files.exists(dir.resolve("mods/lib-1.0.jar")))
        assertEquals(V2, h.repo.get(instance.id)?.pinnedPackVersion)
        assertTrue(h.journal.listPending().isEmpty())
        assertEquals(1, h.updater.listSnapshots(instance).size, "every update can be undone, not only a structural one")
    }

    @Test
    fun `an update that fails midway is rolled back to the version it left`() = runTest {
        val h = Harness(breakV2 = true)
        val instance = h.installV1()
        val dir = h.clientDirOf(instance)
        val recordBefore = dir.resolve(PackFileRecord.FILE_NAME).readText()

        runCatching { h.updater.applyUpdate(instance, null, null) }
            .onSuccess { error("a build with a missing file must not apply") }

        assertEquals("COOL-MOD", dir.resolve("mods/cool.jar").readText(), "a mod that already landed is put back")
        assertTrue(Files.exists(dir.resolve("mods/lib-1.0.jar")))
        assertFalse(Files.exists(dir.resolve("mods/lib-2.0.jar")))
        assertEquals("FOO", dir.resolve("config/foo.txt").readText())
        assertEquals(recordBefore, dir.resolve(PackFileRecord.FILE_NAME).readText())
        assertEquals(V1, h.repo.get(instance.id)?.pinnedPackVersion)
        assertTrue(h.journal.listPending().isEmpty(), "rolled back in process, so nothing for the next start")
        assertTrue(h.updater.listSnapshots(instance).isEmpty(), "and the spent snapshot is gone")
    }

    /**
     * The two defects this path shared with the mirror's before it had a guard of its
     * own: an override rewritten in place changed the snapshot's hardlinked copy with
     * it, and the record was never captured, so a rollback put back the new bytes
     * under a record describing the build that had been undone.
     */
    @Test
    fun `a rollback restores the old version's files and the record that describes them`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        val dir = h.clientDirOf(instance)
        val recordBefore = dir.resolve(PackFileRecord.FILE_NAME).readText()
        h.updater.applyUpdate(instance, null, null)

        h.updater.rollback(instance, h.updater.listSnapshots(instance).single().id)

        assertEquals("FOO", dir.resolve("config/foo.txt").readText(), "the override the update rewrote")
        assertEquals("GONE", dir.resolve("config/gone.txt").readText(), "the override it retired")
        assertEquals("COOL-MOD", dir.resolve("mods/cool.jar").readText())
        assertTrue(Files.exists(dir.resolve("mods/lib-1.0.jar")))
        assertFalse(Files.exists(dir.resolve("mods/lib-2.0.jar")), "what only the new version shipped is removed")
        assertEquals(recordBefore, dir.resolve(PackFileRecord.FILE_NAME).readText())
        assertEquals(V1, h.repo.get(instance.id)?.pinnedPackVersion)
    }

    private companion object {
        const val PROJECT = "proj"
        const val V1 = "1.0.0"
        const val V2 = "2.0.0"
        const val VERSIONS_URL = "https://api.modrinth.com/v2/project/proj/version"
        const val PACK_V1_URL = "https://cdn.test/pack-v1.mrpack"
        const val PACK_V2_URL = "https://cdn.test/pack-v2.mrpack"
        const val COOL_V1_URL = "https://cdn.test/cool-v1.jar"
        const val COOL_V2_URL = "https://cdn.test/cool-v2.jar"
        const val LIB_V1_URL = "https://cdn.test/lib-1.jar"
        const val LIB_V2_URL = "https://cdn.test/lib-2.jar"
        const val MISSING_URL = "https://cdn.test/missing.jar"
        const val MC_MANIFEST_URL = "https://piston-meta.test/manifest.json"
        const val MC_VERSION_URL = "https://piston-meta.test/1.20.1.json"
        const val ASSET_INDEX_URL = "https://piston-meta.test/assets/8.json"
        const val CLIENT_URL = "https://piston-data.test/client.jar"
        const val RES_BASE = "https://resources.test"
    }
}
