package hivens.update

import hivens.core.update.LauncherPatch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LauncherUpdaterTest {

    private fun deleteTree(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
    private fun write(root: Path, rel: String, content: String) {
        val p = root.resolve(rel); p.createParentDirectories(); Files.writeString(p, content)
    }

    private class DirSource(val newDir: Path) : AssetSource {
        override fun fetchFile(path: String, dest: Path) { dest.parent?.let { Files.createDirectories(it) }; Files.copy(newDir.resolve(path), dest) }
        override fun fetchPatch(patch: LauncherPatch, dest: Path) = error("no patches in this fixture")
    }

    @Test
    fun updateAppliesAndRecordsManifestThenReportsUpToDate() {
        val base = Files.createTempDirectory("updater")
        try {
            val live = base.resolve("live"); Files.createDirectories(live)
            write(live, "lib/nexira.jar", "v1")
            write(live, "runtime/x", "rt")
            val layout = InstallLayout(live)
            // Record the initial baseline manifest (as the bootstrap would).
            LayoutManifest.write(layout.manifestFile, LayoutManifest.scan(live, excludes = setOf(layout.stagingDir)))

            val server = base.resolve("new"); Files.createDirectories(server)
            write(server, "lib/nexira.jar", "v2-changed")
            write(server, "runtime/x", "rt")
            write(server, "natives/added", "n")
            val remote = LayoutManifest.scan(server)

            val updater = LauncherUpdater(layout)
            val outcome = updater.update(remote, emptyMap(), DirSource(server), "2.0.0")

            val applied = assertIs<UpdateOutcome.Applied>(outcome)
            assertEquals("2.0.0", applied.version)
            assertEquals(2, applied.changed) // jar updated + native added
            assertContentEquals("v2-changed".toByteArray(), Files.readAllBytes(live.resolve("lib/nexira.jar")))
            assertEquals("n", Files.readString(live.resolve("natives/added")))
            assertEquals("2.0.0", Files.readString(layout.versionFile))

            // Second run against the same target: manifest now matches -> no work.
            assertIs<UpdateOutcome.UpToDate>(updater.update(remote, emptyMap(), DirSource(server), "2.0.0"))
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun noRecordedManifestFallsBackToScanning() {
        val base = Files.createTempDirectory("updater")
        try {
            val live = base.resolve("live"); Files.createDirectories(live)
            write(live, "lib/nexira.jar", "v1")
            val layout = InstallLayout(live) // no manifest.json written

            val server = base.resolve("new"); Files.createDirectories(server)
            write(server, "lib/nexira.jar", "v1") // identical -> scan baseline == remote
            val remote = LayoutManifest.scan(server)

            assertIs<UpdateOutcome.UpToDate>(LauncherUpdater(layout).update(remote, emptyMap(), DirSource(server), "1.0.0"))
            assertTrue(true)
        } finally {
            deleteTree(base)
        }
    }
}
