package hivens.source.mirror

import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.update.UpdateCheck
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.PackInstaller
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.smrt.SmrtSyncService
import hivens.launcher.sources.PackArt
import hivens.launcher.sources.PackArtResolver
import hivens.launcher.sources.RoutingPackUpdater
import hivens.launcher.sources.SourceWorks
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.PackUpdateService
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The mirror's installs, updates, reattach, rollback and cover as the engine reaches them
 * now, through the mirror's own work found by the id its records carry, over a mock
 * mirror that records every request.
 *
 * The request lists are the ones the same steps made while the engine called the mirror's
 * installer and updater itself, taken before the work moved behind the sources: a step
 * that asks the mirror one thing more, or another address, fails here.
 */
class MirrorPackWorkTest {
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

    private val req1 = "REQ-1".toByteArray()
    private val req2 = "REQ-2".toByteArray()
    private val cfg = "CFG".toByteArray()
    private val client = "CLIENT".toByteArray()
    private val emptyIndex = """{"objects":{}}"""

    /** Every build declares the SmartyCraft server it joins, as the mirror writes it. */
    private fun manifest(v: String, req: ByteArray, reqUrl: String) =
        """{"schema_version":2,"pack_id":"test","pack_version":"$v","generated_at":"now",
            "minecraft":{"version":"1.20.1"},"loader":{"name":"vanilla","version":""},"java":{"major":17},
            "auth":{"kind":"smartycraft","server_id":"Industrial"},
            "mods":[{"filename":"req.jar","sha1":"${sha1(req)}","size_bytes":${req.size},"required":true,"source":{"type":"smrt_static","url":"$reqUrl"}}],
            "assets":[{"dest":"config/x.cfg","sha1":"${sha1(cfg)}","size_bytes":${cfg.size},"required":true,"source":{"type":"smrt_static","url":"$M/x.cfg"}}]}"""

    private inner class Harness {
        val asked: MutableList<String> = Collections.synchronizedList(ArrayList())
        var latest = "1.0.0"
        private val jsonH = headersOf(HttpHeaders.ContentType, "application/json")
        private val engine = MockEngine { r ->
            val u = r.url.toString()
            asked += "${r.method.value} $u"
            when (u) {
                "$M/v1/packs/test" -> respond(
                    """{"pack_id":"test","display_name":"Test","tagline":"t","minecraft_version":"1.20.1","latest_pack_version":"$latest","icon_url":"$M/icon.png"}""",
                    HttpStatusCode.OK, jsonH,
                )
                "$M/v1/packs/test/manifest" ->
                    respond(if (latest == "1.0.0") manifest("1.0.0", req1, "$M/req-1.jar") else manifest("2.0.0", req2, "$M/req-2.jar"), HttpStatusCode.OK, jsonH)
                "$M/v1/packs/test/manifest/1.0.0" -> respond(manifest("1.0.0", req1, "$M/req-1.jar"), HttpStatusCode.OK, jsonH)
                "$M/v1/packs/test/manifest/2.0.0" -> respond(manifest("2.0.0", req2, "$M/req-2.jar"), HttpStatusCode.OK, jsonH)
                "$M/v1/packs/test/manifest/versions" -> respond(
                    """{"schema_version":2,"pack_id":"test","latest":"$latest","builds":[
                    {"version_number":"2.0.0","version_type":"release","date_published":"2026-02-02T00:00:00Z"},
                    {"version_number":"1.0.0","version_type":"release","date_published":"2026-01-01T00:00:00Z"}]}""",
                    HttpStatusCode.OK, jsonH,
                )
                "$M/req-1.jar" -> respond(ByteReadChannel(req1), HttpStatusCode.OK)
                "$M/req-2.jar" -> respond(ByteReadChannel(req2), HttpStatusCode.OK)
                "$M/x.cfg" -> respond(ByteReadChannel(cfg), HttpStatusCode.OK)
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
        val dataDir = tempDir("mirror-work")
        private val provider = HttpClientProvider { HttpClient(engine) }
        private val transfers = testTransferEngine(provider)
        val repo = Repo()
        private val provisioner = RuntimeProvisioner(
            librariesDir = tempDir("libs"), assetsDir = tempDir("assets"), clientProvider = provider,
            transfers = transfers, json = json, loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MC, resourcesBaseUrl = "https://resources.test",
        )
        private val mirror = SmrtPackClient(provider, M, json)
        private val sync = SmrtSyncService(ModrinthClient(provider, transfers, json), transfers)
        private val snapshots = PackSnapshotService(dataDir, json)
        private val journal = ApplyJournal(dataDir, json)
        val work = MirrorPackWork(mirror, PackInstaller(sync, provisioner, repo, dataDir), PackUpdateService(mirror, sync, repo, snapshots, journal, dataDir))
        val sources = SourceWorks.of(PackOrigin(MirrorPackSource.ID) to work)
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

        /** What [block] asked the mirror, sorted, since the files of one step are fetched side by side. */
        suspend fun askedBy(block: suspend () -> Unit): List<String> {
            asked.clear()
            block()
            return asked.sorted()
        }

        suspend fun install(): PackInstance =
            installs.install(CataloguePack(PackOrigin(MirrorPackSource.ID), "test", "Test", "t"), CataloguePackVersion("1.0.0", "1.0.0", "1.0.0"))
    }

    @Test
    fun `every step asks the mirror what it asked before the work moved behind the sources`() = runBlocking {
        val h = Harness()
        lateinit var installed: PackInstance
        assertEquals(
            listOf(
                "GET $M/req-1.jar", "GET $M/v1/packs/test", "GET $M/v1/packs/test/manifest/1.0.0", "GET $M/x.cfg",
                "GET $CL", "GET $MCV", "GET $AI", "GET $MC",
            ),
            h.askedBy { installed = h.install() },
            "install",
        )
        h.latest = "2.0.0"
        assertEquals(
            listOf("GET $M/v1/packs/test/manifest", "GET $M/v1/packs/test/manifest/1.0.0", "GET $M/v1/packs/test/manifest/versions"),
            h.askedBy { h.updater.checkForUpdate(h.repo.get(installed.id)!!) },
            "check",
        )
        assertEquals(
            listOf("GET $M/req-2.jar", "GET $M/v1/packs/test/manifest", "GET $M/v1/packs/test/manifest/1.0.0"),
            h.askedBy { h.updater.applyUpdate(h.repo.get(installed.id)!!, null) },
            "apply",
        )
        assertEquals(listOf("GET $M/v1/packs/test"), h.askedBy { h.art.resolve(h.repo.get(installed.id)!!) }, "art")
        assertEquals(
            listOf("GET $M/v1/packs/test/manifest/1.0.0"),
            h.askedBy { h.updater.rollback(h.repo.get(installed.id)!!, h.updater.listSnapshots(h.repo.get(installed.id)!!).first().id) },
            "rollback",
        )
        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        lateinit var preview: ReattachPreview
        assertEquals(
            listOf("GET $M/v1/packs/test/manifest/1.0.0", "GET $M/v1/packs/test/manifest/1.0.0", "GET $M/v1/packs/test/manifest/versions"),
            h.askedBy { preview = h.instances.previewReattach(detached) },
            "preview reattach",
        )
        assertEquals(
            listOf("GET $M/v1/packs/test/manifest/1.0.0", "GET $M/v1/packs/test/manifest/1.0.0"),
            h.askedBy { h.instances.reattach(detached, preview.confirmation) },
            "reattach",
        )
        assertEquals(listOf("GET $M/v1/packs/test/manifest/versions"), h.askedBy { h.updater.availableBuilds(h.repo.get(installed.id)!!) }, "builds")
    }

    /**
     * The server binding is what a SmartyCraft launch reads to join, so a mirror pack keeps
     * it through everything the mirror does to it, and only a detach, which hands the pack
     * to its player, takes it off until the reattach writes it back.
     */
    @Test
    fun `a pack bound to its server stays bound through install, update, rollback and reattach`() = runBlocking {
        val h = Harness()
        val bound = PackAuthRequirement.SmartyCraft("Industrial")
        val installed = h.install()
        assertEquals(bound, installed.cachedManifest?.authRequirement, "installed")

        h.latest = "2.0.0"
        assertIs<UpdateCheck.Available>(h.updater.checkForUpdate(installed))
        h.updater.applyUpdate(installed, null)
        assertEquals("2.0.0", h.repo.get(installed.id)?.pinnedPackVersion)
        assertEquals(bound, h.repo.get(installed.id)?.cachedManifest?.authRequirement, "updated")

        h.updater.rollback(h.repo.get(installed.id)!!, h.updater.listSnapshots(installed).first().id)
        assertEquals("1.0.0", h.repo.get(installed.id)?.pinnedPackVersion)
        assertEquals(bound, h.repo.get(installed.id)?.cachedManifest?.authRequirement, "rolled back")

        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        assertNull(detached.cachedManifest?.authRequirement, "a detached pack is its player's and launches unbound")
        val back = h.instances.reattach(detached, h.instances.previewReattach(detached).confirmation)
        assertEquals(PackOrigin(MirrorPackSource.ID), back.packRef.origin)
        assertEquals(bound, back.cachedManifest?.authRequirement, "reattached")
        assertEquals(PackArt("$M/icon.png", null), h.art.resolve(back))
    }

    private companion object {
        const val M = "https://mirror.test"
        const val MC = "https://piston-meta.test/manifest.json"
        const val MCV = "https://piston-meta.test/1.20.1.json"
        const val AI = "https://piston-meta.test/assets/8.json"
        const val CL = "https://piston-data.test/client.jar"
    }
}
