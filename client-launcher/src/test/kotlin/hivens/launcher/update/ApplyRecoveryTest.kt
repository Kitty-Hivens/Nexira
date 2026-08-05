package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class FakeRepo : IPackRepository {
        val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): Flow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = map.values.toList()
        override suspend fun get(id: String): PackInstance? = map[id]
        override suspend fun put(instance: PackInstance) { map[instance.id] = instance; flow.value = map.values.toList() }
        override suspend fun delete(id: String) { map.remove(id) }
    }

    /** A repository whose write is interrupted, as a shutdown mid-rollback interrupts it. */
    private class CancellingRepo : IPackRepository {
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): Flow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance): Unit = throw CancellationException("shutting down")
        override suspend fun delete(id: String) = Unit
    }

    private fun instance(id: String, dir: String, followLatest: Boolean = true) = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Mirror, "pack", "5"),
        displayName = dir,
        instanceDirName = dir,
        createdAtEpoch = 0L,
        pinnedPackVersion = "5",
        followLatest = followLatest,
    )

    @Test
    fun `journal round-trips begin, list, complete`() {
        val dataDir = Files.createTempDirectory("aj")
        val journal = ApplyJournal(dataDir, json)
        assertTrue(journal.listPending().isEmpty())
        val entry = PendingApply("1", "inst", "snap-1", "5", "6", listOf("mods/a.jar"), 100L)
        journal.begin(entry)
        assertEquals(listOf(entry), journal.listPending())
        journal.complete("inst")
        assertTrue(journal.listPending().isEmpty())
    }

    @Test
    fun `recoverInterrupted rolls a half-applied instance back to its snapshot`() = runTest {
        val dataDir = Files.createTempDirectory("rec")
        val dir = "industrial"
        val clientDir = dataDir.resolve("instances").resolve(dir)
        val modsDir = clientDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.writeString(modsDir.resolve("a.jar"), "old-a")

        val snapshots = PackSnapshotService(dataDir, json)
        val journal = ApplyJournal(dataDir, json)
        val repo = FakeRepo()
        val preUpdate = instance("1", dir, followLatest = true)
        val managed = setOf("mods/a.jar", "mods/b.jar")
        val snap = snapshots.capture(clientDir, preUpdate, managed, "snap-1", 100L)

        // Simulate a partial apply then a crash: managed file replaced (new inode,
        // like the real ATOMIC_MOVE), a new managed file added, marker left, no commit.
        Files.delete(modsDir.resolve("a.jar"))
        Files.writeString(modsDir.resolve("a.jar"), "new-a")
        Files.writeString(modsDir.resolve("b.jar"), "new-b")
        journal.begin(PendingApply("1", dir, snap.id, "5", "6", managed.toList(), 100L))

        val recovered = ApplyRecovery(snapshots, repo, journal, dataDir).recoverInterrupted()

        assertEquals(listOf(dir), recovered)
        assertEquals("old-a", Files.readString(modsDir.resolve("a.jar")), "captured file restored to pre-update bytes")
        assertFalse(Files.exists(modsDir.resolve("b.jar")), "apply-added file removed")
        assertEquals(false, repo.get("1")?.followLatest, "recovered instance is pinned")
        assertTrue(journal.listPending().isEmpty(), "marker cleared")
        assertTrue(snapshots.list(dir).isEmpty(), "snapshot consumed")
    }

    @Test
    fun `a rollback interrupted by shutdown keeps its marker for the next start`() = runTest {
        val dataDir = Files.createTempDirectory("rec3")
        val dir = "industrial"
        val clientDir = dataDir.resolve("instances").resolve(dir)
        val modsDir = clientDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.writeString(modsDir.resolve("a.jar"), "old-a")

        val snapshots = PackSnapshotService(dataDir, json)
        val journal = ApplyJournal(dataDir, json)
        val managed = setOf("mods/a.jar")
        val snap = snapshots.capture(clientDir, instance("1", dir), managed, "snap-1", 100L)
        Files.writeString(modsDir.resolve("a.jar"), "new-a")
        val entry = PendingApply("1", dir, snap.id, "5", "6", managed.toList(), 100L)
        journal.begin(entry)

        val recovery = ApplyRecovery(snapshots, CancellingRepo(), journal, dataDir)
        val outcome = runCatching { recovery.recoverInterrupted() }

        assertTrue(outcome.exceptionOrNull() is CancellationException, "the cancellation must propagate, not be logged as a failure")
        assertEquals(
            listOf(entry), journal.listPending(),
            "the marker is the only thing that brings the next start back to this instance",
        )
    }

    @Test
    fun `recoverInterrupted clears the marker when the snapshot is gone`() = runTest {
        val dataDir = Files.createTempDirectory("rec2")
        val journal = ApplyJournal(dataDir, json)
        journal.begin(PendingApply("1", "gone", "missing-snap", "5", "6", listOf("mods/a.jar"), 100L))

        val recovered = ApplyRecovery(PackSnapshotService(dataDir, json), FakeRepo(), journal, dataDir).recoverInterrupted()

        assertTrue(recovered.isEmpty())
        assertTrue(journal.listPending().isEmpty(), "unrecoverable marker cleared so it does not loop every boot")
    }
}
