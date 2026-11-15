package hivens.catalogue

import hivens.catalogue.api.BuildDownload
import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.ProjectBuild
import hivens.catalogue.api.SourceAbility
import hivens.core.api.HttpClientProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.net.Digest
import hivens.core.net.DigestAlgorithm
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.mrpack.MrpackSource
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.smrt.ModStraySweep
import hivens.launcher.smrt.SmrtSyncService
import hivens.launcher.smrt.SyncStraySweep
import hivens.launcher.sources.PackArt
import hivens.launcher.sources.PackArtResolver
import hivens.launcher.sources.RoutingPackUpdater
import hivens.launcher.sources.SourceCannotException
import hivens.launcher.sources.SourceInstaller
import hivens.launcher.sources.SourceMissingException
import hivens.launcher.sources.SourceOperation
import hivens.launcher.sources.SourcePackWork
import hivens.launcher.sources.SourceWorks
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.ArchiveBuild
import hivens.launcher.update.ArchiveFeed
import hivens.launcher.update.ArchiveFile
import hivens.launcher.update.ArchivePackUpdater
import hivens.launcher.update.PackReattacher
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.SnapshotRollback
import hivens.test.testTransferEngine
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.get
import hivens.widget.api.registerModuleKeys
import hivens.widget.api.service
import hivens.widget.model.ScopeLevel
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * A source a third module brings installs, updates and reattaches its packs through its own
 * contribution, end to end on a temporary data directory, with nothing in the engine, the
 * catalogue or the packs module naming it. The engine reaches its work the way the host
 * hands it over: through the listing the catalogue opened for its id.
 *
 * What the third module writes is the crate below: its listing, a feed of its builds as
 * archives, and the few lines that hand the engine's own archive installer and updater
 * that feed. And once its module is taken away, its packs say their source is missing
 * where the source is needed, and do everything else as before.
 */
@OptIn(HostApi::class)
class ThirdSourceWorkTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val temps = mutableListOf<Path>()
    private val apps = ArrayList<WidgetScope>()

    @AfterTest
    fun tearDown() {
        apps.forEach { it.close() }
        temps.forEach { it.toFile().deleteRecursively() }
    }

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

    private val tool1 = "TOOL-1".toByteArray()
    private val tool2 = "TOOL-2".toByteArray()
    private val client = "CLIENT".toByteArray()
    private val emptyIndex = """{"objects":{}}"""

    private fun archiveOf(second: Boolean): ByteArray {
        val file = tempDir("crate").resolve("box.mrpack")
        val tool = if (second) tool2 to "$CRATE_CDN/tool-2.jar" else tool1 to "$CRATE_CDN/tool-1.jar"
        val index = """{"formatVersion":1,"game":"minecraft","versionId":"${if (second) V2 else V1}","name":"Box",
            "dependencies":{"minecraft":"1.20.1"},
            "files":[{"path":"mods/tool.jar","hashes":{"sha1":"${sha1(tool.first)}"},"downloads":["${tool.second}"],"fileSize":${tool.first.size}}]}"""
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            z.putNextEntry(ZipEntry("modrinth.index.json")); z.write(index.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("overrides/config/box.txt")); z.write(if (second) "BOX-2".toByteArray() else "BOX".toByteArray()); z.closeEntry()
        }
        return Files.readAllBytes(file)
    }

    private val box1 = archiveOf(false)
    private val box2 = archiveOf(true)

    /** The crate's builds of its one pack, newest first, as its own catalogue would publish them. */
    private val builds = listOf(
        ArchiveBuild("box-2", "box", V2, "release", "2026-02-02T00:00:00Z", listOf("1.20.1"), emptyList(), null, ArchiveFile("$CRATE_CDN/box-2.mrpack", sha1(box2))),
        ArchiveBuild("box-1", "box", V1, "release", "2026-01-01T00:00:00Z", listOf("1.20.1"), emptyList(), null, ArchiveFile("$CRATE_CDN/box-1.mrpack", sha1(box1))),
    )

    /** What the crate's module writes to have its builds installed and updated by the engine. */
    private inner class CrateFeed(private val transfers: TransferEngine) : ArchiveFeed {
        override val sourceName: String = "The Crate"
        override suspend fun builds(packId: String): List<ArchiveBuild> = builds.filter { it.packId == packId }
        override suspend fun download(url: String, target: Path, sha1: String) {
            if (Files.exists(target)) return
            transfers.fetch(Transfer(url = url, dest = target, expect = Digest(DigestAlgorithm.SHA1, sha1)))
        }
        override suspend fun title(packId: String): String = "Box"
    }

    /** The crate's answers as a source: its listing, and its work over the engine's archive machinery. */
    private inner class CratePacks(private val mrpack: MrpackInstaller, archives: ArchivePackUpdater) : PackListing, SourcePackWork {
        override suspend fun search(query: String, page: Int): List<CatalogueCard> = listOf(CatalogueCard(CRATE, "box", "Box", "A box of tools"))
        override suspend fun details(packId: String): PackDetails = PackDetails(CRATE, packId, "Box", "A box of tools", iconUrl = ICON, versions = versions(packId))
        override suspend fun versions(packId: String): List<ProjectBuild> = builds.map { b ->
            ProjectBuild(
                id = b.id, name = b.versionNumber, versionNumber = b.versionNumber, versionType = b.versionType,
                gameVersions = b.gameVersions, loaders = b.loaders, datePublished = b.datePublished,
                download = BuildDownload(url = b.archiveFile().url, sha1 = b.archiveFile().sha1, size = -1L),
            )
        }

        override val installer: SourceInstaller = SourceInstaller { pack, version, onReserveDir, progress ->
            mrpack.installFromUrl(
                url = assertNotNull(version.downloadUrl),
                sha1 = assertNotNull(version.downloadSha1),
                size = version.downloadSize,
                source = MrpackSource(PackOrigin(CRATE), pack.id, version.versionNumber, buildKey = version.id),
                iconUrl = pack.iconUrl,
                onReserveDir = onReserveDir,
                progress = progress,
            )
        }
        override val updater: PackUpdater = archives
        override val reattacher: PackReattacher = archives
        override suspend fun art(packId: String): PackArt = PackArt(ICON, BANNER)
    }

    /** The crate's module: what answers for it, made where the module is trusted with the engine. */
    private inner class CrateKeys : KeyContribution {
        override val id: String = "crate.keys"
        override val label: String = "Crate"
        override fun declare(keys: ModuleKeys) {
            keys.service(ScopeLevel.App) { context ->
                val host = context.hostServices
                val mrpack = host.get<MrpackInstaller>()
                CratePacks(
                    mrpack,
                    ArchivePackUpdater(
                        feed = CrateFeed(host.get<TransferEngine>()),
                        installer = mrpack,
                        repository = host.get<IPackRepository>(),
                        snapshotService = host.get<PackSnapshotService>(),
                        journal = host.get<ApplyJournal>(),
                        dataDir = host.get<Path>(),
                        sweep = host.get<ModStraySweep>(),
                    ),
                )
            }
        }
    }

    /** The crate as a pack source, declared the way any module declares one. */
    private object CrateSource : PackSourceContribution {
        override val id: String = CRATE
        override val label: String = "crate.name"
        override fun open(services: LevelServices): PackListing = services.get<ThirdSourceWorkTest.CratePacks>()
    }

    /** [compiled] is whether the crate's module is one compiled into the launcher, as it has to be to bring work. */
    private inner class Harness(compiled: Boolean = true) {
        val taken = MutableStateFlow<List<TakenContribution<ModuleContribution>>>(listOf(TakenContribution(CRATE_MODULE, CrateSource)))
        private val compiledIn = if (compiled) taken.value else emptyList()
        val dataDir = tempDir("crate-data")
        val repo = Repo()
        private val engine = MockEngine { r ->
            val jsonH = headersOf(HttpHeaders.ContentType, "application/json")
            when (r.url.toString()) {
                "$CRATE_CDN/box-1.mrpack" -> respond(ByteReadChannel(box1), HttpStatusCode.OK)
                "$CRATE_CDN/box-2.mrpack" -> respond(ByteReadChannel(box2), HttpStatusCode.OK)
                "$CRATE_CDN/tool-1.jar" -> respond(ByteReadChannel(tool1), HttpStatusCode.OK)
                "$CRATE_CDN/tool-2.jar" -> respond(ByteReadChannel(tool2), HttpStatusCode.OK)
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
        private val provider = HttpClientProvider { HttpClient(engine) }
        private val transfers = testTransferEngine(provider)
        private val provisioner = RuntimeProvisioner(
            librariesDir = tempDir("libs"), assetsDir = tempDir("assets"), clientProvider = provider,
            transfers = transfers, json = json, loaderRegistry = LoaderRegistry(emptyList()), osName = "Linux",
            versionManifestUrl = MC, resourcesBaseUrl = "https://resources.test",
        )
        private val snapshots = PackSnapshotService(dataDir, json)
        private val journal = ApplyJournal(dataDir, json)

        /** The engine as the host keeps it: what the catalogue and a module trusted with it read. */
        private val held: Map<KClass<*>, Any> = mapOf(
            TakenContributions::class to object : TakenContributions {
                override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = taken
                override val compiledIn: List<TakenContribution<ModuleContribution>> = this@Harness.compiledIn
            },
            IPackRepository::class to repo,
            PlatformPaths::class to PlatformPaths(osName = "linux", home = dataDir, bootstrapDataDir = { null }, env = { name -> if (name == "NEXIRA_DATA_DIR") dataDir.toString() else null }),
            Path::class to dataDir,
            TransferEngine::class to transfers,
            MrpackInstaller::class to MrpackInstaller(transfers, json, java, provisioner, repo, dataDir),
            PackSnapshotService::class to snapshots,
            ApplyJournal::class to journal,
            ModStraySweep::class to SyncStraySweep(SmrtSyncService(ModrinthClient(provider, transfers, json), transfers)),
        )

        private val engineServices = object : LevelServicesHost {
            override val services = object : LevelServices {
                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> find(type: KClass<T>): T? = held[type] as T?
                override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
            }
            override fun child(level: ScopeLevel): LevelServicesHost = this
            override fun close() = Unit
        }

        /** The catalogue module and the crate's, both compiled in and trusted as the launcher trusts its own. */
        val app: WidgetScope = run {
            val sources = WidgetDataRegistry()
            val commands = WidgetCommandRegistry()
            val services = ContributedServices()
            assertIs<ModuleKeysResult.Registered>(registerModuleKeys(CatalogueModule.id, CatalogueKeysContribution, sources, commands, services, trusted = true))
            assertIs<ModuleKeysResult.Registered>(registerModuleKeys(CRATE_MODULE, CrateKeys(), sources, commands, services, trusted = true))
            WidgetScope.app(sources, services.host(engineServices, Dispatchers.Unconfined), Dispatchers.Unconfined, commands).also { apps += it }
        }

        /** Handed over as the host hands it: the work the catalogue holds for the id, if any. */
        val works = SourceWorks { origin -> app.services.get<SourceShelf>().work(origin.id) }
        val installs = PackInstallCoordinator(works)
        val updater = RoutingPackUpdater(works, SnapshotRollback(snapshots, repo, dataDir))
        val art = PackArtResolver(works)
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
            sources = works,
        )

        fun dirOf(instance: PackInstance): Path = dataDir.resolve("instances").resolve(instance.instanceDirName)

        /** Installs the crate's first build the way an install request reaches the engine: by the page the source answers. */
        suspend fun install(): PackInstance {
            val page = assertNotNull(app.services.get<SourceShelf>().listing(CRATE)).details("box")
            val build = page.versions.first { it.id == "box-1" }
            return installs.install(
                CataloguePack(PackOrigin(page.origin), page.id, page.title, page.tagline, iconUrl = page.iconUrl),
                CataloguePackVersion(build.id, build.name, build.versionNumber, downloadUrl = build.download?.url, downloadSha1 = build.download?.sha1),
            )
        }
    }

    @Test
    fun `a third source installs, updates and reattaches its packs through its own contribution`() = runBlocking {
        val h = Harness()
        val listed = assertNotNull(h.app.find(CatalogueKeys.Sources)).state.value.single()
        assertEquals(setOf(SourceAbility.Install, SourceAbility.Update, SourceAbility.Reattach), listed.abilities, "the catalogue lists what its work holds")

        val installed = h.install()
        val dir = h.dirOf(installed)
        assertEquals(PackOrigin(CRATE), installed.packRef.origin)
        assertEquals(V1, installed.pinnedPackVersion)
        assertEquals("TOOL-1", Files.readString(dir.resolve("mods/tool.jar")))
        assertEquals("BOX", Files.readString(dir.resolve("config/box.txt")))
        assertEquals(PackArt(ICON, BANNER), h.art.resolve(installed), "the cover the source publishes")

        assertTrue(h.updater.handles(installed))
        val check = assertIs<UpdateCheck.Available>(h.updater.checkForUpdate(installed))
        assertEquals(V2, check.toVersion)
        assertIs<UpdateOutcome.Applied>(h.updater.applyUpdate(installed, null))
        assertEquals("TOOL-2", Files.readString(dir.resolve("mods/tool.jar")))
        assertEquals("BOX-2", Files.readString(dir.resolve("config/box.txt")))
        assertEquals(V2, h.repo.get(installed.id)?.pinnedPackVersion)

        val detached = h.instances.detachToLocal(h.repo.get(installed.id)!!)
        assertEquals(PackOrigin.Local, detached.packRef.origin)
        Files.writeString(dir.resolve("mods/mine.jar"), "MINE")
        Files.writeString(dir.resolve("mods/tool.jar"), "TOOL-PATCHED")
        assertTrue(h.instances.canReattach(detached))

        val preview = h.instances.previewReattach(detached)
        assertEquals("box-2", preview.build)
        assertEquals(listOf("mods/mine.jar"), preview.removed)
        assertEquals(listOf("mods/tool.jar"), preview.replaced)
        val back = h.instances.reattach(detached, preview.confirmation)

        assertEquals(PackOrigin(CRATE), back.packRef.origin)
        assertNull(back.forkedFrom)
        assertEquals("TOOL-2", Files.readString(dir.resolve("mods/tool.jar")))
        assertFalse(Files.exists(dir.resolve("mods/mine.jar")), "the mods are the build's again")
    }

    /**
     * Taken away, the crate's packs keep everything that does not need it: they are still
     * in the library, detach and delete as ever and keep the cover they captured. Updating,
     * reattaching and installing say the source is missing, and nothing is touched.
     */
    @Test
    fun `a pack whose source is not loaded says so where the source is needed and works everywhere else`() = runBlocking {
        val h = Harness()
        val installed = h.install()
        val dir = h.dirOf(installed)
        val reattachable = h.instances.detachToLocal(h.install())

        h.taken.value = emptyList()

        assertFalse(h.updater.handles(installed), "no update is offered")
        assertEquals(UpdateCheck.UpToDate, h.updater.checkForUpdate(installed), "and a background check passes it by")
        val update = assertFailsWith<SourceMissingException> { h.updater.applyUpdate(installed, null) }
        assertEquals(PackOrigin(CRATE), update.origin)
        assertFailsWith<SourceMissingException> { h.updater.previewSwitch(installed, V2) }
        assertEquals("TOOL-1", Files.readString(dir.resolve("mods/tool.jar")), "nothing was touched")

        assertFalse(h.instances.canReattach(reattachable))
        assertTrue(h.instances.canReattachAfterDetach(installed), "a detach while the source is away is not final")
        assertEquals(PackOrigin(CRATE), h.instances.reattachSourceMissing(reattachable))
        assertFailsWith<SourceMissingException> { h.instances.previewReattach(reattachable) }
        assertFailsWith<SourceMissingException> { h.instances.reattach(reattachable, ReattachConfirmation("box-1", "x")) }
        assertEquals(PackOrigin.Local, h.repo.get(reattachable.id)?.packRef?.origin)

        val missingInstall = assertFailsWith<SourceMissingException> {
            h.installs.install(CataloguePack(PackOrigin(CRATE), "box", "Box", ""), CataloguePackVersion("box-1", V1, V1))
        }
        assertEquals(PackOrigin(CRATE), missingInstall.origin)

        assertEquals(PackArt(ICON, null), h.art.resolve(installed), "the cover the install captured")
        assertFalse(h.art.answered(installed), "and no answer is kept for a source that said nothing")
        val renamed = installed.copy(displayName = "My box")
        h.repo.put(renamed)
        assertEquals("My box", h.repo.get(installed.id)?.displayName)
        val detached = h.instances.detachToLocal(renamed)
        assertEquals(PackOrigin.Local, detached.packRef.origin)
        assertEquals(PackInstanceService.DeleteOutcome.Deleted, h.instances.deleteCompletely(detached))
        assertFalse(Files.exists(dir))

        h.taken.value = listOf(TakenContribution(CRATE_MODULE, CrateSource))
        assertEquals(PackArt(ICON, BANNER), h.art.resolve(reattachable), "with its module back, the source is asked again")
        assertTrue(h.instances.canReattach(reattachable))
    }

    /** A snapshot is the pack's own, so a pack whose source is away is still rolled back. */
    @Test
    fun `a pack whose source is not loaded rolls back an update without it`() = runBlocking {
        val h = Harness()
        val installed = h.install()
        val dir = h.dirOf(installed)
        assertIs<UpdateOutcome.Applied>(h.updater.applyUpdate(installed, null))
        assertEquals("TOOL-2", Files.readString(dir.resolve("mods/tool.jar")))

        h.taken.value = emptyList()

        val snapshot = h.updater.listSnapshots(installed).single()
        val back = h.updater.rollback(h.repo.get(installed.id)!!, snapshot.id)
        assertEquals(V1, back.pinnedPackVersion)
        assertEquals("TOOL-1", Files.readString(dir.resolve("mods/tool.jar")))
        assertEquals("BOX", Files.readString(dir.resolve("config/box.txt")))
    }

    /**
     * A source from a module that is not compiled into the launcher, a jar, lists and
     * nothing else, whatever its listing implements: no install, update or reattach is
     * handed to it, and the catalogue says it can do none of them.
     */
    @Test
    fun `a jar's source is handed no work whatever its listing offers`() = runBlocking {
        val h = Harness(compiled = false)
        assertEquals(emptySet(), assertNotNull(h.app.find(CatalogueKeys.Sources)).state.value.single().abilities)
        assertEquals(SourceWorks.ListingOnly, h.works.work(PackOrigin(CRATE)))
        val refused = assertFailsWith<SourceCannotException> { h.install() }
        assertEquals(SourceOperation.Install, refused.operation)
        assertTrue(h.repo.map.isEmpty(), "nothing was installed")
    }

    /** A source whose opening failed is not opened again, nor logged again, until its module brings another instance. */
    @Test
    fun `a source that could not be opened is not asked again until it is a new instance`() {
        val opens = AtomicInteger()
        class Broken : PackSourceContribution {
            override val id: String = "Broken"
            override val label: String = "broken.name"
            override fun open(services: LevelServices): PackListing {
                opens.incrementAndGet()
                throw IllegalStateException("cannot open")
            }
        }
        val h = Harness()
        h.taken.value = listOf(TakenContribution("test.broken", Broken()))
        val shelf = h.app.services.get<SourceShelf>()

        assertNull(shelf.listing("Broken"))
        assertNull(shelf.listing("Broken"))
        assertNull(h.works.work(PackOrigin("Broken")))
        assertEquals(1, opens.get(), "opened once, and the failure kept")

        h.taken.value = listOf(TakenContribution("test.broken", Broken()))
        assertNull(shelf.listing("Broken"))
        assertEquals(2, opens.get(), "a new instance is tried afresh")
    }

    private companion object {
        const val CRATE = "Crate"
        const val CRATE_MODULE = "test.crate"
        const val CRATE_CDN = "https://crate.test"
        const val ICON = "$CRATE_CDN/box.png"
        const val BANNER = "$CRATE_CDN/banner.png"
        const val V1 = "1.0.0"
        const val V2 = "2.0.0"
        const val MC = "https://piston-meta.test/manifest.json"
        const val MCV = "https://piston-meta.test/1.20.1.json"
        const val AI = "https://piston-meta.test/assets/8.json"
        const val CL = "https://piston-data.test/client.jar"
    }
}
