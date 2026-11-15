package hivens.source.modrinth

import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.smrt.SmrtSyncService
import hivens.launcher.smrt.SyncStraySweep
import hivens.launcher.sources.PackArtResolver
import hivens.launcher.sources.RoutingPackUpdater
import hivens.launcher.sources.SourceWorks
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.ArchivePackUpdater
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.ReattachPreview
import hivens.test.testTransferEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Modrinth's installs, updates, reattach, rollback and cover as the engine reaches them
 * now, through Modrinth's own work found by the id its records carry, over a mock
 * Modrinth and CDN that record every request.
 *
 * The request lists are the ones the same steps made while the engine called the
 * Modrinth installer and updater itself, taken before the work moved behind the
 * sources. The game's runtime is in place first, as it was then.
 */
class ModrinthPackWorkTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(prefix: String): Path = Files.createTempDirectory(prefix).also { temps.add(it) }
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private class Repo : IPackRepository {
        val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list() = map.values.toList()
        override suspend fun get(id: String) = map[id]
        override suspend fun put(instance: PackInstance) {
            map[instance.id] = instance
            flow.value = map.values.toList()
        }
        override suspend fun delete(id: String) {
            map.remove(id)
            flow.value = map.values.toList()
        }
    }

    private val java = object : IJavaManager {
        override suspend fun getJavaPath(version: String): Path = Path.of("/bin/java")
        override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path = Path.of("/bin/java")
    }

    private val cool1 = "COOL-1".toByteArray()
    private val cool2 = "COOL-2".toByteArray()
    private val client = "CLIENT".toByteArray()
    private val emptyIndex = """{"objects":{}}"""

    private fun archiveOf(second: Boolean): ByteArray {
        val file = tempDir("pack").resolve("p.mrpack")
        val cool = if (second) cool2 to "$CDN/cool-2.jar" else cool1 to "$CDN/cool-1.jar"
        val index = """{"formatVersion":1,"game":"minecraft","versionId":"${if (second) "2.0.0" else "1.0.0"}","name":"P",
            "dependencies":{"minecraft":"1.20.1"},
            "files":[{"path":"mods/cool.jar","hashes":{"sha1":"${sha1(cool.first)}"},"downloads":["${cool.second}"],"fileSize":${cool.first.size}}]}"""
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            z.putNextEntry(ZipEntry("modrinth.index.json")); z.write(index.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("overrides/config/foo.txt")); z.write(if (second) "FOO-2".toByteArray() else "FOO".toByteArray()); z.closeEntry()
        }
        return Files.readAllBytes(file)
    }

    private inner class Harness {
        val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
        val pack1 = archiveOf(false)
        private val pack2 = archiveOf(true)
        private val jsonH = headersOf(HttpHeaders.ContentType, "application/json")
        private fun version(id: String, n: String, d: String, url: String, b: ByteArray) =
            """{"id":"$id","project_id":"proj","version_number":"$n","date_published":"$d","game_versions":["1.20.1"],"loaders":[],
               "files":[{"hashes":{"sha1":"${sha1(b)}"},"url":"$url","filename":"p.mrpack","primary":true,"size":${b.size}}]}"""
        private val engine = MockEngine { r ->
            val u = r.url.toString()
            asked += "${r.method.value} $u"
            when (u) {
                VERSIONS -> respond(
                    "[" + version("v2id", "2.0.0", "2026-02-02T00:00:00Z", "$CDN/p2.mrpack", pack2) + "," +
                        version("v1id", "1.0.0", "2026-01-01T00:00:00Z", "$CDN/p1.mrpack", pack1) + "]",
                    HttpStatusCode.OK, jsonH,
                )
                PROJECT -> respond("""{"id":"proj","slug":"p","title":"P","description":"","icon_url":"$CDN/icon.png","gallery":[]}""", HttpStatusCode.OK, jsonH)
                "$CDN/p1.mrpack" -> respond(ByteReadChannel(pack1), HttpStatusCode.OK)
                "$CDN/p2.mrpack" -> respond(ByteReadChannel(pack2), HttpStatusCode.OK)
                "$CDN/cool-1.jar" -> respond(ByteReadChannel(cool1), HttpStatusCode.OK)
                "$CDN/cool-2.jar" -> respond(ByteReadChannel(cool2), HttpStatusCode.OK)
                MC -> respond("""{"versions":[{"id":"1.20.1","url":"$MCV"}]}""", HttpStatusCode.OK, jsonH)
                MCV -> respond(
                    """{"assetIndex":{"id":"8","sha1":"${sha1(emptyIndex.toByteArray())}","size":${emptyIndex.length},"url":"$AI"},
                    "downloads":{"client":{"sha1":"${sha1(client)}","size":${client.size},"url":"$CL"}},"libraries":[]}""",
                    HttpStatusCode.OK, jsonH,
                )
                AI -> respond(emptyIndex, HttpStatusCode.OK, jsonH)
                CL -> respond(ByteReadChannel(client), HttpStatusCode.OK)
                else -> respond("missing", HttpStatusCode.NotFound)
            }
        }
        val dataDir = tempDir("modrinth-work")
        private val provider = HttpClientProvider { HttpClient(engine) }
        private val transfers = testTransferEngine(provider)
        val repo = Repo()
        val provisioner = RuntimeProvisioner(
            librariesDir = tempDir("libs"), assetsDir = tempDir("assets"), clientProvider = provider,
            transfers = transfers, json = json, loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MC, resourcesBaseUrl = "https://resources.test",
        )
        private val modrinth = ModrinthClient(provider, transfers, json)
        private val snapshots = PackSnapshotService(dataDir, json)
        private val journal = ApplyJournal(dataDir, json)
        private val mrpack = MrpackInstaller(transfers, json, java, provisioner, repo, dataDir)
        private val archives = ArchivePackUpdater(ModrinthArchiveFeed(modrinth), mrpack, repo, snapshots, journal, dataDir, SyncStraySweep(SmrtSyncService(modrinth, transfers)))
        val sources = SourceWorks.of(PackOrigin(ModrinthPackSource.ID) to ModrinthPackWork(modrinth, mrpack, archives))
        val installs = PackInstallCoordinator(sources)
        val updater = RoutingPackUpdater(sources)
        val art = PackArtResolver(sources)
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
            sources = sources,
        )

        /** What [block] asked, sorted, since the files of one step are fetched side by side. */
        suspend fun askedBy(block: suspend () -> Unit): List<String> {
            asked.clear()
            block()
            return asked.sorted()
        }
    }

    @Test
    fun `every step asks Modrinth what it asked before the work moved behind the sources`() = runBlocking {
        val h = Harness()
        h.provisioner.ensureRuntime("1.20.1", "vanilla", "")
        lateinit var installed: PackInstance
        assertEquals(
            listOf("GET $CDN/cool-1.jar", "GET $CDN/p1.mrpack"),
            h.askedBy {
                installed = h.installs.install(
                    CataloguePack(PackOrigin(ModrinthPackSource.ID), "proj", "P", "", iconUrl = "$CDN/icon.png"),
                    CataloguePackVersion("v1id", "1.0.0", "1.0.0", downloadUrl = "$CDN/p1.mrpack", downloadSha1 = sha1(h.pack1), downloadSize = h.pack1.size.toLong()),
                )
            },
            "install",
        )
        assertEquals(listOf("GET $VERSIONS"), h.askedBy { h.updater.checkForUpdate(h.repo.get(installed.id)!!) }, "check")
        assertEquals(
            listOf("GET $VERSIONS", "GET $CDN/cool-2.jar", "GET $CDN/p2.mrpack"),
            h.askedBy { h.updater.applyUpdate(h.repo.get(installed.id)!!, null) },
            "apply",
        )
        assertEquals(listOf("GET $PROJECT"), h.askedBy { h.art.resolve(h.repo.get(installed.id)!!) }, "art")
        assertEquals(
            emptyList(),
            h.askedBy { h.updater.rollback(h.repo.get(installed.id)!!, h.updater.listSnapshots(h.repo.get(installed.id)!!).first().id) },
            "rollback",
        )
        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        lateinit var preview: ReattachPreview
        assertEquals(listOf("GET $VERSIONS", "GET $CDN/p1.mrpack"), h.askedBy { preview = h.instances.previewReattach(detached) }, "preview reattach")
        assertEquals(listOf("GET $VERSIONS"), h.askedBy { h.instances.reattach(detached, preview.confirmation) }, "reattach")
        assertEquals(listOf("GET $VERSIONS"), h.askedBy { h.updater.availableBuilds(h.repo.get(installed.id)!!) }, "builds")
    }

    private companion object {
        const val CDN = "https://cdn.test"
        const val VERSIONS = "https://api.modrinth.com/v2/project/proj/version"
        const val PROJECT = "https://api.modrinth.com/v2/project/proj"
        const val MC = "https://piston-meta.test/manifest.json"
        const val MCV = "https://piston-meta.test/1.20.1.json"
        const val AI = "https://piston-meta.test/assets/8.json"
        const val CL = "https://piston-data.test/client.jar"
    }
}
