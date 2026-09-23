package hivens.launcher.instance

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.PendingApply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackInstanceServiceTest {

    private class FakeRepo : IPackRepository {
        val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = map.values.toList()
        override suspend fun get(id: String): PackInstance? = map[id]
        override suspend fun put(instance: PackInstance) {
            map[instance.id] = instance
            flow.value = map.values.toList()
        }
        override suspend fun delete(id: String) {
            map.remove(id)
            flow.value = map.values.toList()
        }
    }

    private val runningId = MutableStateFlow<String?>(null)
    private val running = object : RunningPackSource {
        override val runningPackInstanceId: StateFlow<String?> = runningId
    }
    private val work = InstanceWorkRegistry()

    private fun service(repo: IPackRepository, data: java.nio.file.Path) = PackInstanceService(
        repo, data, running, work,
        snapshots = PackSnapshotService(data, json),
        journal = ApplyJournal(data, json),
        sizes = InstanceSizeService(data, CoroutineScope(Dispatchers.Unconfined)),
    )
    private val json = Json { ignoreUnknownKeys = true }

    private fun instance(id: String, dir: String = id, origin: PackOrigin = PackOrigin.Mirror) = PackInstance(
        id = id,
        packRef = PackReference(origin, "pack", "2026.01.01"),
        displayName = id,
        instanceDirName = dir,
        createdAtEpoch = 0L,
    )

    @Test
    fun `deleteCompletely removes the files and the registry entry`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = data.resolve("instances").resolve("industrial")
        Files.createDirectories(instDir.resolve("mods"))
        Files.writeString(instDir.resolve("mods").resolve("a.jar"), "x")
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)

        val outcome = service(repo, data).deleteCompletely(pack)

        assertEquals(DeleteOutcome.Deleted, outcome)
        assertFalse(Files.exists(instDir), "instance dir removed")
        assertNull(repo.get("1"), "registry entry dropped")
    }

    @Test
    fun `deleteCompletely on a missing dir still drops the entry`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1", dir = "gone")
        repo.put(pack)

        assertEquals(DeleteOutcome.Deleted, service(repo, data).deleteCompletely(pack))
        assertNull(repo.get("1"))
    }

    @Test
    fun `detachToLocal flips origin to Local and records provenance`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1", origin = PackOrigin.Mirror)
        repo.put(pack)

        val detached = service(repo, data).detachToLocal(pack)

        assertEquals(PackOrigin.Local, detached.packRef.origin)
        assertEquals(pack.packRef, detached.forkedFrom)
        assertEquals(PackOrigin.Local, repo.get("1")?.packRef?.origin, "persisted as Local")
    }

    /**
     * On Linux the whole tree went from under the live game, which kept writing its
     * world into a directory that no longer existed.
     */
    @Test
    fun `a pack whose game is running is not deleted`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = Files.createDirectories(data.resolve("instances").resolve("industrial"))
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        runningId.value = "1"

        assertEquals(DeleteOutcome.GameRunning, service(repo, data).deleteCompletely(pack))
        assertTrue(Files.exists(instDir))
        assertEquals(pack, repo.get("1"))
    }

    @Test
    fun `a pack being rewritten is not deleted under the work`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = Files.createDirectories(data.resolve("instances").resolve("industrial"))
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        val release = CompletableDeferred<Unit>()
        launch { work.during("1", InstanceWork.Update) { release.await() } }
        advanceUntilIdle()

        assertEquals(DeleteOutcome.Busy(InstanceWork.Update), service(repo, data).deleteCompletely(pack))
        assertTrue(Files.exists(instDir))
        release.complete(Unit)
    }

    /** Another pack's game is none of this one's business. */
    @Test
    fun `a different pack's game does not hold the delete`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        runningId.value = "2"

        assertEquals(DeleteOutcome.Deleted, service(repo, data).deleteCompletely(pack))
    }

    /** Up to three snapshots of a deleted pack's files used to stay on disk for good. */
    @Test
    fun `deleting a pack removes its snapshots and its journal marker too`() = runTest {
        val data = Files.createTempDirectory("pis")
        val instDir = Files.createDirectories(data.resolve("instances").resolve("industrial").resolve("mods"))
        Files.writeString(instDir.resolve("a.jar"), "x")
        val repo = FakeRepo()
        val pack = instance("1", dir = "industrial")
        repo.put(pack)
        PackSnapshotService(data, json).capture(data.resolve("instances/industrial"), pack, setOf("mods/a.jar"), "snap-1", 1L)
        ApplyJournal(data, json).begin(PendingApply("1", "industrial", "snap-1", "1", "2", listOf("mods/a.jar"), 1L))

        assertEquals(DeleteOutcome.Deleted, service(repo, data).deleteCompletely(pack))

        assertFalse(Files.exists(data.resolve("snapshots/industrial")), "snapshots gone")
        assertNull(ApplyJournal(data, json).read("industrial"), "and the marker, which recovery would otherwise chase")
        data.toFile().deleteRecursively()
    }

    /**
     * Detached, the pack stayed bound: the launch swept mods/ against the mirror's
     * baseline and deleted what the now-unlocked Content tab had let the player add.
     */
    @Test
    fun `a detached pack is no longer bound to its server`() = runTest {
        val data = Files.createTempDirectory("pis")
        val repo = FakeRepo()
        val bound = instance("1").copy(
            cachedManifest = CachedManifestSnapshot("1.12.2", "forge", "14.23.5.2860", 8, PackAuthRequirement.SmartyCraft("Industrial")),
        )
        repo.put(bound)

        val detached = service(repo, data).detachToLocal(bound)

        assertNull(detached.cachedManifest?.authRequirement)
        assertNull(repo.get("1")?.cachedManifest?.authRequirement, "persisted unbound")
        assertEquals("forge", repo.get("1")?.cachedManifest?.loaderName, "and nothing else about the runtime moves")
    }
}
