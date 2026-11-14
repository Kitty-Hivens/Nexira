package hivens.launcher.update

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.update.CompatChange
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.update.UpdateOutcome
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.mrpack.MrpackSource
import hivens.core.io.InstanceMutationLock
import hivens.launcher.record.PackFileRecord
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.smrt.SmrtSyncService
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private fun buildPack(second: Boolean, libV2Url: String = LIB_V2_URL, inner: Boolean = false): ByteArray {
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
            if (inner) {
                zos.putNextEntry(ZipEntry("overrides/mods/1.20.1/inner.jar")); zos.write("INNER".toByteArray()); zos.closeEntry()
            }
        }
        return Files.readAllBytes(file)
    }

    /**
     * [archiveGate] runs before the second version's archive is served, so a test can
     * hold the download open. Every archive request is counted in [archiveFetches].
     */
    private inner class Harness(
        breakV2: Boolean = false,
        private val archiveGate: suspend () -> Unit = {},
        /** Whether both versions place a jar of their own in `mods/1.20.1/`. */
        inner: Boolean = false,
    ) {
        val archiveFetches = AtomicInteger()
        val dataDir = tempDir("data")
        val repo = FakeRepo()
        private val packV1 = buildPack(second = false, inner = inner)
        // A broken build names a lib the CDN does not have, so its fetch fails after
        // the changed mod may already have landed.
        val packV2 = buildPack(second = true, libV2Url = if (breakV2) MISSING_URL else LIB_V2_URL, inner = inner)

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
                PACK_V2_URL -> {
                    archiveFetches.incrementAndGet()
                    archiveGate()
                    respond(ByteReadChannel(packV2), HttpStatusCode.OK)
                }
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
        val installer = MrpackInstaller(transfers, json, fakeJava, provisioner, repo, dataDir)
        val snapshots = PackSnapshotService(dataDir, json)
        val journal = ApplyJournal(dataDir, json)
        private val modrinth = ModrinthClient(provider, transfers, json)
        val updater = ModrinthPackUpdater(modrinth, installer, repo, snapshots, journal, dataDir, SmrtSyncService(modrinth, transfers))

        /** Detach and reattach as the launcher runs them, with Modrinth as the only source that takes a pack back. */
        val instances = PackInstanceService(
            repository = repo,
            dataDir = dataDir,
            running = object : RunningPackSource {
                override val runningPackInstanceId: StateFlow<String?> = MutableStateFlow(null)
            },
            work = InstanceWorkRegistry(),
            snapshots = snapshots,
            journal = journal,
            sizes = InstanceSizeService(dataDir, CoroutineScope(Dispatchers.Unconfined)),
            reattachers = mapOf(PackOrigin.Modrinth to updater),
        )

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

    /** The mirror path's case, through the archive path: a delete that held the lock first is final. */
    @Test
    fun `an update that finds its instance deleted writes nothing`() = runTest {
        val h = Harness()
        val instance = h.installV1()
        val dir = h.clientDirOf(instance)
        dir.toFile().deleteRecursively()
        h.repo.delete(instance.id)

        assertFailsWith<InstanceRemovedException> { h.updater.applyUpdate(instance, null, null) }

        assertFalse(Files.exists(dir), "the deleted directory stays deleted")
        assertTrue(h.journal.listPending().isEmpty())
    }

    /**
     * A Modrinth fork goes back the way a mirror one does: its mods are the source
     * build's again, whatever the player did to them, and the record names Modrinth.
     */
    @Test
    fun `a Modrinth fork is previewed and put back on its source's build`() = runTest {
        val h = Harness()
        val detached = h.instances.detachToLocal(h.installV1())
        val dir = h.clientDirOf(detached)
        Files.writeString(dir.resolve("mods/extra.jar"), "MINE")
        Files.writeString(dir.resolve("mods/cool.jar"), "COOL-MOD-PATCHED")
        Files.delete(dir.resolve("config/foo.txt"))
        Files.writeString(dir.resolve("config/mine.txt"), "player settings")

        val preview = h.instances.previewReattach(detached)

        assertEquals(V2, preview.toVersion, "it follows the latest, so the latest")
        assertEquals("v2id", preview.build)
        assertEquals(CompatChange.Same, preview.compat)
        assertEquals(listOf("config/gone.txt", "mods/extra.jar", "mods/lib-1.0.jar"), preview.removed)
        assertEquals(listOf("config/foo.txt", "mods/lib-2.0.jar"), preview.restored)
        assertEquals(listOf("mods/cool.jar"), preview.replaced)

        val back = h.instances.reattach(detached, preview.confirmation)

        assertEquals(PackReference(PackOrigin.Modrinth, PROJECT, V2), back.packRef)
        assertEquals(null, back.forkedFrom)
        assertEquals("v2id", back.installedBuildKey)
        assertEquals(back, h.repo.get(detached.id))
        assertEquals("COOL-MOD-V2", dir.resolve("mods/cool.jar").readText())
        assertTrue(Files.exists(dir.resolve("mods/lib-2.0.jar")))
        assertFalse(Files.exists(dir.resolve("mods/lib-1.0.jar")))
        assertFalse(Files.exists(dir.resolve("mods/extra.jar")), "a mod the player added is gone")
        assertEquals("FOO-V2", dir.resolve("config/foo.txt").readText())
        assertFalse(Files.exists(dir.resolve("config/gone.txt")))
        assertEquals("player settings", dir.resolve("config/mine.txt").readText(), "what the build never shipped is not its business")
        assertTrue(h.journal.listPending().isEmpty())
        assertEquals(1, h.archiveFetches.get(), "the archive the preview fetched is the one the reattach used")
        assertFalse(Files.exists(reattachArchiveDir(h.dataDir, detached.instanceDirName)), "and it is gone once the reattach is done")
    }

    /**
     * A reattach updates the way an ordinary Modrinth update does, with `mods/` held to
     * the build: a jar edit the record cannot see, since it kept the size, is put back,
     * and a config edit the build did not change stays.
     */
    @Test
    fun `a reattach holds mods to the build and keeps a config edit the build did not change`() = runTest {
        val h = Harness()
        val installed = h.installV1()
        h.repo.put(installed.copy(followLatest = false))
        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        val dir = h.clientDirOf(detached)
        Files.writeString(dir.resolve("mods/cool.jar"), "COOL-MAD")
        Files.writeString(dir.resolve("config/foo.txt"), "BAR")

        val preview = h.instances.previewReattach(detached)
        assertEquals(V1, preview.toVersion, "pinned, and Modrinth still lists the pin")
        assertEquals(listOf("mods/cool.jar"), preview.replaced, "the config edit is not listed, since it stays")
        assertTrue(preview.removed.isEmpty() && preview.restored.isEmpty())

        h.instances.reattach(detached, preview.confirmation)

        assertEquals("COOL-MOD", dir.resolve("mods/cool.jar").readText())
        assertEquals("BAR", dir.resolve("config/foo.txt").readText())
        assertEquals(V1, h.repo.get(installed.id)?.pinnedPackVersion)
    }

    /**
     * The cleanup ran in a context switch, and a cancelled coroutine refuses to switch,
     * so a preview cancelled mid-download left its archive behind.
     */
    @Test
    fun `a preview cancelled while the archive downloads leaves nothing behind`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val h = Harness(archiveGate = { entered.complete(Unit); awaitCancellation() })
        val detached = h.instances.detachToLocal(h.installV1())

        val preview = launch(Dispatchers.IO) { h.instances.previewReattach(detached) }
        entered.await()
        preview.cancelAndJoin()

        assertFalse(Files.exists(reattachArchiveDir(h.dataDir, detached.instanceDirName)), "nothing of the archive stays")
    }

    @Test
    fun `a pack file switched off by its name is switched back on`() = runTest {
        val h = Harness()
        val installed = h.installV1()
        h.repo.put(installed.copy(followLatest = false))
        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        val dir = h.clientDirOf(detached)
        Files.move(dir.resolve("mods/cool.jar"), dir.resolve("mods/cool.jar.disabled"))

        val preview = h.instances.previewReattach(detached)
        assertEquals(listOf("mods/cool.jar"), preview.turnedOn)
        assertTrue(preview.restored.isEmpty(), "not fetched again beside the copy that is there")

        h.instances.reattach(detached, preview.confirmation)
        assertEquals("COOL-MOD", dir.resolve("mods/cool.jar").readText())
        assertFalse(Files.exists(dir.resolve("mods/cool.jar.disabled")))
    }

    /**
     * An update reads the record when it starts and writes the build onto it when it
     * ends. Writing the reference it had read put the source back over a detach made
     * in between.
     */
    @Test
    fun `an update that started before a detach does not undo it`() = runTest {
        val h = Harness()
        val installed = h.installV1()
        val detached = h.instances.detachToLocal(installed)
        val archive = tempDir("dl").resolve("v2.mrpack").also { Files.write(it, h.packV2) }

        // The record as the update read it before the detach.
        h.installer.update(installed, archive, MrpackSource(PackOrigin.Modrinth, PROJECT, V2, buildKey = "v2id"))

        val record = h.repo.get(installed.id)!!
        assertEquals(PackOrigin.Local, record.packRef.origin, "still detached")
        assertEquals(detached.forkedFrom, record.forkedFrom)
        assertEquals(V2, record.pinnedPackVersion, "with the build the update placed")
    }

    /**
     * The strays were held to the jars the build puts at the top of `mods/`, so one it
     * places in a versioned folder of its own read as foreign and was deleted right
     * after the update had put it there.
     */
    @Test
    fun `a jar the build places in a versioned folder is its own`() = runTest {
        val h = Harness(inner = true)
        val installed = h.installV1()
        h.repo.put(installed.copy(followLatest = false))
        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        val dir = h.clientDirOf(detached)
        assertEquals("INNER", dir.resolve("mods/1.20.1/inner.jar").readText())

        val preview = h.instances.previewReattach(detached)
        assertFalse(preview.removed.any { "inner.jar" in it }, "not listed: ${preview.removed}")

        h.instances.reattach(detached, preview.confirmation)
        assertEquals("INNER", dir.resolve("mods/1.20.1/inner.jar").readText())
    }

    /**
     * A cancelled preview's cleanup and a new preview's tidying each deleted the
     * instance's archive folder, which a running reattach was reading its archive
     * from. The reattach now holds an archive of its own.
     */
    @Test
    fun `a preview that starts and fails while a reattach runs leaves the reattach its archive`() = runBlocking {
        val failing = AtomicBoolean(false)
        val h = Harness(archiveGate = { if (failing.get()) throw IOException("cut off") })
        val detached = h.instances.detachToLocal(h.installV1())
        val shown = h.instances.previewReattach(detached)
        val home = reattachArchiveDir(h.dataDir, detached.instanceDirName)

        // The reattach takes the preview's archive over, then waits on the lock the
        // test holds, while a second preview starts and dies and cleans up after itself.
        val clientDir = h.clientDirOf(detached)
        val reattach = InstanceMutationLock.withLock(clientDir) {
            val running = launch(Dispatchers.IO) { h.instances.reattach(detached, shown.confirmation) }
            while (Files.list(home).use { entries -> entries.noneMatch { it.fileName.toString().startsWith("apply-") } }) delay(10)
            failing.set(true)
            val failed = runCatching { h.instances.previewReattach(detached) }
            assertTrue(failed.isFailure, "the second preview dies on its download")
            h.instances.discardReattachPreview(detached)
            failing.set(false)
            running
        }
        val fetchedBefore = h.archiveFetches.get()
        reattach.join()

        assertEquals(PackOrigin.Modrinth, h.repo.get(detached.id)?.packRef?.origin, "the reattach finished on the archive it held")
        assertEquals(fetchedBefore, h.archiveFetches.get(), "and did not have to fetch it again")
    }

    /** As on the mirror: a version the player picked is a pin, and only the latest is followed. */
    @Test
    fun `a version picked by hand stops following the latest`() = runTest {
        val h = Harness()
        val instance = h.installV1()

        assertTrue(h.updater.applyUpdate(instance, null, null) is UpdateOutcome.Applied)
        assertEquals(true, h.repo.get(instance.id)?.followLatest, "an update to the latest stays on the latest")

        assertTrue(h.updater.applyUpdate(h.repo.get(instance.id)!!, "v1id", null) is UpdateOutcome.Applied)
        assertEquals(false, h.repo.get(instance.id)?.followLatest)
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
