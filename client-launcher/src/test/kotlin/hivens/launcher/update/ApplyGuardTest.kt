package hivens.launcher.update

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApplyGuardTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    /**
     * Suspends before it writes, as the real registry does. A write that never
     * suspends never notices a cancellation, which is what hid the rollback being
     * cut short on exactly the write that commits it.
     */
    private class FakeRepo : IPackRepository {
        val map = LinkedHashMap<String, PackInstance>()
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = map.values.toList()
        override suspend fun get(id: String): PackInstance? = map[id]
        override suspend fun put(instance: PackInstance) { yield(); map[instance.id] = instance }
        override suspend fun delete(id: String) { map.remove(id) }
    }

    private inner class Harness {
        val dataDir: Path = Files.createTempDirectory("guard").also { temps.add(it) }
        val clientDir: Path = dataDir.resolve("instances").resolve(DIR)
        val mods: Path = clientDir.resolve("mods")
        val repo = FakeRepo()
        val snapshots = PackSnapshotService(dataDir, json)
        val journal = ApplyJournal(dataDir, json)
        val guard = ApplyGuard(snapshots, journal, repo)
        val before = PackInstance(
            id = "1",
            packRef = PackReference(PackOrigin.Mirror, "pack", "1"),
            displayName = DIR,
            instanceDirName = DIR,
            createdAtEpoch = 0L,
            pinnedPackVersion = "1",
        )

        init {
            Files.createDirectories(mods)
            Files.writeString(mods.resolve("a.jar"), "old-a")
            repo.map[before.id] = before
        }

        /** The commit landing, and a game's exit recording playtime in the same stretch. */
        suspend fun commitAndPlay() {
            repo.put(repo.get("1")!!.copy(pinnedPackVersion = "2", playtimeSeconds = 600))
        }

        /** What an apply does to the files: one replaced through a new inode, one added. */
        fun rewriteFiles() {
            Files.delete(mods.resolve("a.jar"))
            Files.writeString(mods.resolve("a.jar"), "new-a")
            Files.writeString(mods.resolve("b.jar"), "new-b")
        }

        suspend fun run(rewrite: suspend () -> Unit) =
            guard.apply(clientDir, before, MANAGED, "1", "2", rewrite)
    }

    @Test
    fun `a finished apply clears its marker and keeps the snapshot to roll back to`() = runTest {
        val h = Harness()
        h.run {
            h.rewriteFiles()
            h.repo.put(h.before.copy(pinnedPackVersion = "2"))
        }

        assertEquals("new-a", Files.readString(h.mods.resolve("a.jar")))
        assertTrue(h.journal.listPending().isEmpty())
        assertEquals(1, h.snapshots.list(DIR).size, "the snapshot is what a manual rollback reads")
    }

    @Test
    fun `a failed apply is rolled back and leaves no marker`() = runTest {
        val h = Harness()
        assertFailsWith<IOException> {
            h.run {
                h.rewriteFiles()
                h.commitAndPlay()
                throw IOException("sha1 mismatch")
            }
        }

        assertEquals("old-a", Files.readString(h.mods.resolve("a.jar")))
        assertFalse(Files.exists(h.mods.resolve("b.jar")))
        assertEquals("1", h.repo.get("1")?.pinnedPackVersion, "the pre-update build is put back")
        assertEquals(600, h.repo.get("1")?.playtimeSeconds, "and only the build: the playtime recorded meanwhile stays")
        assertTrue(h.journal.listPending().isEmpty())
        assertTrue(h.snapshots.list(DIR).isEmpty(), "a snapshot that was spent on the rollback is gone")
    }

    /**
     * A cancelled apply used to be treated as a failure whose restore could itself be
     * cancelled, and the marker was cleared either way: an instance half on the new
     * build, the registry on the old one, and nothing left that would look at it.
     */
    @Test
    fun `a cancelled apply is still rolled back, and the cancellation goes on`() = runTest {
        val h = Harness()
        val parked = CompletableDeferred<Unit>()
        var outcome: Throwable? = null
        val job = launch {
            outcome = runCatching {
                h.run {
                    h.rewriteFiles()
                    h.commitAndPlay()
                    parked.await()
                }
            }.exceptionOrNull()
        }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()

        assertTrue(outcome is CancellationException, "the cancellation reaches the caller, got $outcome")
        assertEquals("old-a", Files.readString(h.mods.resolve("a.jar")), "the restore ran to the end")
        assertFalse(Files.exists(h.mods.resolve("b.jar")))
        assertEquals("1", h.repo.get("1")?.pinnedPackVersion)
        assertTrue(h.journal.listPending().isEmpty(), "nothing is left half-done, so nothing is left to recover")
    }

    @Test
    fun `a rollback that fails keeps the marker so the next start tries again`() = runTest {
        val h = Harness()
        val failure = assertFailsWith<IOException> {
            h.run {
                h.rewriteFiles()
                // The restore needs these bytes, and without them it cannot finish.
                h.snapshots.list(DIR).forEach { snap ->
                    Files.delete(h.dataDir.resolve("snapshots/$DIR/${snap.id}/files/mods/a.jar"))
                }
                throw IOException("sha1 mismatch")
            }
        }

        assertEquals(1, h.journal.listPending().size, "the marker is the next start's way back here")
        assertTrue(failure.suppressed.any { it is SnapshotRestoreException }, "and the restore failure travels with the cause")
    }

    private companion object {
        const val DIR = "inst"
        val MANAGED = setOf("mods/a.jar", "mods/b.jar")
    }
}
