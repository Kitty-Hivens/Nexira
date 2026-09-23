package hivens.launcher.update

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.io.AtomicFiles
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackSnapshotServiceTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun instance(dir: String) = PackInstance(
        id = "1",
        packRef = PackReference(PackOrigin.Mirror, "pack", "5"),
        displayName = dir,
        instanceDirName = dir,
        createdAtEpoch = 0L,
        pinnedPackVersion = "5",
    )

    @Test
    fun `capture then restore round-trips the pre-update bytes and record`() {
        val dataDir = Files.createTempDirectory("snap")
        val dir = "inst"
        val clientDir = dataDir.resolve("instances").resolve(dir)
        val modsDir = clientDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.writeString(modsDir.resolve("a.jar"), "old-a")
        val svc = PackSnapshotService(dataDir, json)
        val managed = setOf("mods/a.jar", "mods/b.jar")
        val snap = svc.capture(clientDir, instance(dir), managed, "s1", 1L)

        // Apply: replace a (new inode, like ATOMIC_MOVE), add b.
        Files.delete(modsDir.resolve("a.jar"))
        Files.writeString(modsDir.resolve("a.jar"), "new-a")
        Files.writeString(modsDir.resolve("b.jar"), "new-b")

        val restored = svc.restore(clientDir, dir, snap.id, managed)

        assertEquals("old-a", Files.readString(modsDir.resolve("a.jar")), "captured file restored")
        assertFalse(Files.exists(modsDir.resolve("b.jar")), "apply-added file removed")
        assertEquals("1", restored.id, "pre-update record returned")
    }

    /**
     * The roster is not a manifest path, so the scan never lists it and a restore
     * driven by the manifest cannot put it back. Left behind, it names the build
     * that was rolled back FROM, and on an instance whose registry holds no
     * baseline to outrank it, that file IS the next launch's delete list: the sweep
     * reads it and removes the restored build's own mods.
     */
    @Test
    fun `a rollback puts back the roster of the build it restores`() {
        val dataDir = Files.createTempDirectory("snap-roster")
        val dir = "inst"
        val clientDir = dataDir.resolve("instances").resolve(dir)
        val modsDir = clientDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.writeString(modsDir.resolve("a.jar"), "old-a")
        Files.writeString(clientDir.resolve(".nexira-mods"), "a.jar\na.jar.disabled")
        Files.writeString(clientDir.resolve(".nexira-sync-source"), "mirror")
        val svc = PackSnapshotService(dataDir, json)
        val managed = setOf("mods/a.jar", "mods/b.jar")
        val snap = svc.capture(clientDir, instance(dir), managed, "s1", 1L)

        // The apply: a.jar is replaced, b.jar arrives, and the roster is rewritten
        // to name the new build.
        //
        // The roster goes through AtomicFiles because that is how the sync publishes
        // it, and the distinction is the one the capture rests on: a hardlink holds
        // the pre-update bytes only while the writer replaces the file (a new inode)
        // rather than truncating it in place, which would rewrite the snapshot's
        // copy along with the live one.
        Files.delete(modsDir.resolve("a.jar"))
        Files.writeString(modsDir.resolve("a.jar"), "new-a")
        Files.writeString(modsDir.resolve("b.jar"), "new-b")
        AtomicFiles.writeString(clientDir.resolve(".nexira-mods"), "a.jar\na.jar.disabled\nb.jar\nb.jar.disabled")

        svc.restore(clientDir, dir, snap.id, managed)

        assertEquals(
            listOf("a.jar", "a.jar.disabled"),
            Files.readAllLines(clientDir.resolve(".nexira-mods")).filter { it.isNotBlank() },
            "the roster has to describe the build that is now on disk, not the one undone",
        )
        assertEquals("mirror", Files.readString(clientDir.resolve(".nexira-sync-source")).trim())
    }

    /** An instance that had no roster when it was captured must not get one invented for it. */
    @Test
    fun `a snapshot taken without a roster restores without inventing one`() {
        val dataDir = Files.createTempDirectory("snap-no-roster")
        val dir = "inst"
        val clientDir = dataDir.resolve("instances").resolve(dir)
        val modsDir = clientDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.writeString(modsDir.resolve("a.jar"), "old-a")
        val svc = PackSnapshotService(dataDir, json)
        val snap = svc.capture(clientDir, instance(dir), setOf("mods/a.jar"), "s1", 1L)

        svc.restore(clientDir, dir, snap.id, setOf("mods/a.jar"))

        assertFalse(
            Files.exists(clientDir.resolve(".nexira-mods")),
            "nothing was captured, so there is nothing to put back",
        )
    }

    /**
     * An instance installed before the roster existed has none to capture, and the
     * update is what writes the first one. That roster names the build being undone,
     * so a rollback that kept it would hand the next launch a delete list for the
     * wrong build on exactly the instance with no baseline to outrank it.
     */
    @Test
    fun `a rollback removes the roster the update wrote when the snapshot had none`() {
        val dataDir = Files.createTempDirectory("snap-roster-born")
        val dir = "inst"
        val clientDir = dataDir.resolve("instances").resolve(dir)
        val modsDir = clientDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.writeString(modsDir.resolve("a.jar"), "old-a")
        val svc = PackSnapshotService(dataDir, json)
        val managed = setOf("mods/a.jar", "mods/b.jar")
        val snap = svc.capture(clientDir, instance(dir), managed, "s1", 1L)

        Files.writeString(modsDir.resolve("b.jar"), "new-b")
        AtomicFiles.writeString(clientDir.resolve(".nexira-mods"), "b.jar\nb.jar.disabled")
        AtomicFiles.writeString(clientDir.resolve(".nexira-sync-source"), "mirror")

        svc.restore(clientDir, dir, snap.id, managed)

        assertFalse(Files.exists(clientDir.resolve(".nexira-mods")), "the update's roster goes with the update")
        assertFalse(Files.exists(clientDir.resolve(".nexira-sync-source")), "so does the marker it wrote")
        assertEquals("old-a", Files.readString(modsDir.resolve("a.jar")))
    }

    @Test
    fun `restore throws when a captured snapshot file is missing`() {
        val dataDir = Files.createTempDirectory("snap2")
        val dir = "inst"
        val clientDir = dataDir.resolve("instances").resolve(dir)
        val modsDir = clientDir.resolve("mods")
        Files.createDirectories(modsDir)
        Files.writeString(modsDir.resolve("a.jar"), "old-a")
        val svc = PackSnapshotService(dataDir, json)
        val snap = svc.capture(clientDir, instance(dir), setOf("mods/a.jar"), "s1", 1L)

        // Corrupt the snapshot: drop its stored copy of the captured file.
        Files.delete(dataDir.resolve("snapshots").resolve(dir).resolve("s1").resolve("files").resolve("mods").resolve("a.jar"))

        val ex = assertFailsWith<SnapshotRestoreException> {
            svc.restore(clientDir, dir, snap.id, setOf("mods/a.jar"))
        }
        assertTrue(ex.failures.any { it.contains("mods/a.jar") }, "names the unresolved path")
    }
}
