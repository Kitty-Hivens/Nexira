package hivens.ui.packs

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWorkRegistry
import hivens.core.update.CompatChange
import hivens.core.update.PackBuild
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.PackReattacher
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreview
import hivens.launcher.update.ReattachPreviewStaleException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The operations the pack commands start, past the seam the command tests stop at:
 * what a reattach and an update leave on the operation the pack's surfaces read.
 */
class PackOperationsTest {

    private val dir: Path = Files.createTempDirectory("pack-operations-test")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private val source = PackReference(PackOrigin.Mirror, "industrial", "5")

    /** A pack its player detached from [source]. */
    private val detached = PackInstance(
        id = "p1",
        packRef = source.copy(origin = PackOrigin.Local),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 1L,
        forkedFrom = source,
    )

    private val confirmed = ReattachConfirmation("5", "fingerprint")

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

    /** A source that takes the pack back, or refuses the preview as no longer holding when [stale]. */
    private class Source(private val stale: Boolean) : PackReattacher {
        override suspend fun previewReattach(instance: PackInstance, source: PackReference): ReattachPreview =
            error("not asked here")

        override suspend fun reattach(
            instance: PackInstance,
            source: PackReference,
            confirmed: ReattachConfirmation,
            progress: ((current: Int, total: Int, path: String) -> Unit)?,
        ): PackInstance {
            if (stale) throw ReattachPreviewStaleException(instance.id)
            progress?.invoke(1, 1, "mods/a.jar")
            return instance.copy(packRef = source.copy(version = "5"), forkedFrom = null, pinnedPackVersion = "5")
        }

        override suspend fun sourceName(source: PackReference): String? = null
    }

    private val operations = PackOperationService(scope, InstanceSizeService(dir, scope), InstanceWorkRegistry())

    private fun instances(reattacher: PackReattacher) = PackInstanceService(
        repository = Packs(listOf(detached)),
        dataDir = dir,
        running = object : RunningPackSource {
            override val runningPackInstanceId: StateFlow<String?> = MutableStateFlow(null)
        },
        work = InstanceWorkRegistry(),
        snapshots = PackSnapshotService(dir, Json),
        journal = ApplyJournal(dir, Json),
        sizes = InstanceSizeService(dir, scope),
        reattachers = mapOf(PackOrigin.Mirror to reattacher),
    )

    private fun ended(id: String): PackOperation = runBlocking {
        withTimeout(5.seconds) { operations.operations.first { it[id]?.isRunning == false } }.getValue(id)
    }

    @Test
    fun `a reattach whose preview no longer holds ends as stale, not as a failure`() {
        assertEquals(true, operations.startReattach(detached, instances(Source(stale = true)), confirmed))
        assertEquals(PackOperation(detached.id, PackOperationKind.Reattach, PackOperationPhase.PreviewStale), ended(detached.id))
    }

    @Test
    fun `a reattach that lands ends on the build it put the pack on`() {
        assertEquals(true, operations.startReattach(detached, instances(Source(stale = false)), confirmed))
        assertEquals(PackOperationPhase.Updated("5"), ended(detached.id).phase)
    }

    /** An update the command starts says where it landed, and clears the waiting badge from inside the operation. */
    @Test
    fun `an update to the latest ends on the build it applied and clears the waiting badge`() {
        val pack = detached.copy(packRef = source, forkedFrom = null, pinnedPackVersion = "5")
        val hub = object : PackUpdateStatusHub {
            override val statuses = MutableStateFlow<Map<String, PackUpdateStatus>>(emptyMap())
            override fun report(id: String, status: PackUpdateStatus) {
                statuses.value = statuses.value + (id to status)
            }
        }
        val updater = object : PackUpdater {
            override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean): UpdateCheck = UpdateCheck.UpToDate
            override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck = UpdateCheck.UpToDate
            override suspend fun applyUpdate(
                instance: PackInstance,
                targetVersion: String?,
                progress: ((current: Int, total: Int, path: String) -> Unit)?,
            ): UpdateOutcome = UpdateOutcome.Applied("6", CompatChange.Same, plan = null)
            override suspend fun availableBuilds(instance: PackInstance): List<PackBuild> = emptyList()
            override fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>> = emptyFlow()
            override fun listSnapshots(instance: PackInstance): List<PackSnapshot> = emptyList()
            override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance = instance
        }

        assertEquals(true, operations.startLatestUpdate(pack, updater, hub))
        assertEquals(PackOperation(pack.id, PackOperationKind.Update, PackOperationPhase.Updated("6")), ended(pack.id))
        assertEquals(mapOf(pack.id to PackUpdateStatus.UpToDate), hub.statuses.value)
    }
}
