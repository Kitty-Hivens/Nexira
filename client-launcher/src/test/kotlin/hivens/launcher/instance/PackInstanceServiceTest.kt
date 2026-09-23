package hivens.launcher.instance

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWork
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.launcher.launch.RunningPackSource
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

    private fun service(repo: IPackRepository, data: java.nio.file.Path) = PackInstanceService(repo, data, running, work)

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
}
