package hivens.launcher.update

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
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
