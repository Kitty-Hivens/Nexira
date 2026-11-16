package hivens.launcher.sources

import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.SnapshotRollback
import hivens.test.stopThenDelete
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * What the engine does with a source that cannot be asked: one whose module is not
 * loaded, one that is loaded and only lists, and one whose own code fails while it is
 * looked up. Every operation such a source cannot do is refused and says so, never
 * answered as done, and what needs nothing from the source goes on.
 */
class SourceRefusalsTest {
    private val dir: Path = Files.createTempDirectory("source-refusals")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    private class Packs(list: List<PackInstance>) : IPackRepository {
        private val all = MutableStateFlow(list)
        override fun observe(): StateFlow<List<PackInstance>> = all
        override suspend fun list(): List<PackInstance> = all.value
        override suspend fun get(id: String): PackInstance? = all.value.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) {
            all.value = all.value.filterNot { it.id == instance.id } + instance
        }
        override suspend fun delete(id: String) {
            all.value = all.value.filterNot { it.id == id }
        }
    }

    private val shelf = PackOrigin("Shelf")
    private val gone = PackOrigin("Gone")

    /** Shelf lists and does nothing else, Gone has no module loaded. */
    private val sources = SourceWorks.of(shelf to SourceWorks.ListingOnly)

    private fun pack(origin: PackOrigin, id: String = "p-$origin") = PackInstance(
        id = id,
        packRef = PackReference(origin, "industrial", "5"),
        displayName = "Industrial",
        instanceDirName = id,
        createdAtEpoch = 1L,
        pinnedPackVersion = "5",
    )

    private fun detachedFrom(origin: PackOrigin) = pack(PackOrigin.Local, "d-$origin").copy(forkedFrom = PackReference(origin, "industrial", "5"))

    private fun instances(packs: IPackRepository = Packs(emptyList())) = PackInstanceService(
        repository = packs,
        dataDir = dir,
        running = object : RunningPackSource {
            override val runningPackInstanceId: StateFlow<String?> = MutableStateFlow(null)
        },
        work = InstanceWorkRegistry(),
        snapshots = PackSnapshotService(dir, Json),
        journal = ApplyJournal(dir, Json),
        sizes = InstanceSizeService(dir, scope),
        sources = sources,
    )

    @Test
    fun `a source that only lists refuses an update and a switch, and offers neither`() = runBlocking {
        val updater = RoutingPackUpdater(sources)
        val listed = pack(shelf)

        assertFalse(updater.handles(listed))
        val update = assertFailsWith<SourceCannotException> { updater.applyUpdate(listed, null) }
        assertEquals(SourceOperation.Update, update.operation)
        assertEquals(shelf, update.origin)
        assertEquals(SourceOperation.Update, assertFailsWith<SourceCannotException> { updater.previewSwitch(listed, "6") }.operation)
    }

    @Test
    fun `a source that only lists refuses an install, and a missing one says it is missing`() = runBlocking {
        val installs = PackInstallCoordinator(sources)
        val install = assertFailsWith<SourceCannotException> {
            installs.install(CataloguePack(shelf, "industrial", "Industrial", ""), CataloguePackVersion("5", "5", "5"))
        }
        assertEquals(SourceOperation.Install, install.operation)
        assertEquals(gone, assertFailsWith<SourceMissingException> {
            installs.install(CataloguePack(gone, "industrial", "Industrial", ""), CataloguePackVersion("5", "5", "5"))
        }.origin)
    }

    @Test
    fun `a pack detached from a source that only lists cannot be reattached, and is told so`() = runBlocking {
        val detached = detachedFrom(shelf)
        val service = instances(Packs(listOf(detached)))

        assertFalse(service.canReattach(detached))
        assertFalse(service.canReattachAfterDetach(pack(shelf)), "a detach from it is for good")
        assertEquals(
            SourceOperation.Reattach,
            assertFailsWith<SourceCannotException> { service.reattach(detached, ReattachConfirmation("5", "x")) }.operation,
        )
    }

    /** The module may come back, and with it the reattach, so a detach while it is away is not final. */
    @Test
    fun `a detach from a source that is not loaded is not called final`() {
        val service = instances()
        assertTrue(service.canReattachAfterDetach(pack(gone)))
        assertFalse(service.canReattachAfterDetach(pack(PackOrigin.Local)), "a pack of the player's own has nothing to go back to")
    }

    @Test
    fun `an operation a source refuses ends on the refusal, not on success or a failure`() {
        val operations = PackOperationService(scope, InstanceSizeService(dir, scope), InstanceWorkRegistry())
        val updater = RoutingPackUpdater(sources)
        val listed = pack(shelf)
        operations.start(listed, PackOperationKind.Update) { _ ->
            updater.applyUpdate(listed, null)
            PackOperationPhase.Updated("5")
        }
        val ended = runBlocking { withTimeout(5.seconds) { operations.operations.first { it[listed.id]?.isRunning == false } } }
        assertEquals(PackOperationPhase.SourceRefused("Shelf", SourceOperation.Update), ended.getValue(listed.id).phase)
    }

    /** Rolling back needs nothing from the source, so a pack whose source is away keeps its snapshots. */
    @Test
    fun `a pack whose source is not loaded lists its snapshots and rolls back without it`() = runBlocking {
        val missing = pack(gone)
        val packs = Packs(listOf(missing))
        val snapshots = PackSnapshotService(dir, Json)
        val clientDir = dir.resolve("instances").resolve(missing.instanceDirName)
        Files.createDirectories(clientDir.resolve("mods"))
        Files.writeString(clientDir.resolve("mods/a.jar"), "OLD")
        val snap = snapshots.capture(clientDir, missing, setOf("mods/a.jar"), "s1", 1L)
        // Replaced as an apply replaces a file, by a new one in its place: the snapshot holds a link to the old.
        Files.delete(clientDir.resolve("mods/a.jar"))
        Files.writeString(clientDir.resolve("mods/a.jar"), "NEW")
        packs.put(missing.copy(pinnedPackVersion = "6", packRef = missing.packRef.copy(version = "6")))

        val updater = RoutingPackUpdater(sources, SnapshotRollback(snapshots, packs, dir))
        assertEquals(listOf(snap.id), updater.listSnapshots(missing).map { it.id })
        val back = updater.rollback(missing, snap.id)

        assertEquals("OLD", Files.readString(clientDir.resolve("mods/a.jar")))
        assertEquals("5", back.pinnedPackVersion)
        assertEquals(false, packs.get(missing.id)?.followLatest, "pinned, as every rollback pins")
        assertEquals(emptyList(), updater.listSnapshots(pack(PackOrigin.Local)), "a pack of the player's own has no snapshots from a source")
    }

    /** A source's code that cannot be linked takes itself out, and never the engine with it. */
    @Test
    fun `a source that fails while it is looked up is missing, and only its own faults are caught`() {
        assertIs<SourceStatus.Missing>(SourceWorks { throw NoClassDefFoundError("hivens/jar/Gone") }.status(gone))
        assertIs<SourceStatus.Missing>(SourceWorks { throw IllegalStateException("broken") }.status(gone))
        assertFailsWith<CancellationException> { SourceWorks { throw CancellationException("stop") }.status(gone) }
        assertFailsWith<StackOverflowError> { SourceWorks { throw StackOverflowError() }.status(gone) }
        assertEquals(SourceStatus.None, SourceWorks { error("never asked") }.status(PackOrigin.Local))
    }
}
