package hivens.update

import hivens.core.data.FileManifest
import hivens.core.data.flatten
import hivens.core.io.AtomicFiles
import hivens.core.update.LauncherPatch
import hivens.core.update.LauncherUpdatePlanner
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateEngineTest {

    private fun write(root: Path, rel: String, content: String): Path {
        val p = root.resolve(rel); p.createParentDirectories(); Files.writeString(p, content); return p
    }
    private fun read(p: Path) = Files.readString(p)
    private fun deleteTree(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    /** Serves whole files from a "new release" dir and patches from a precomputed map. */
    private class FakeSource(val newDir: Path, val patchFiles: Map<String, Path>) : AssetSource {
        override fun fetchFile(path: String, dest: Path) {
            dest.parent?.let { Files.createDirectories(it) }
            Files.copy(newDir.resolve(path), dest)
        }
        override fun fetchPatch(patch: LauncherPatch, dest: Path) {
            dest.parent?.let { Files.createDirectories(it) }
            Files.copy(patchFiles.getValue(patch.path), dest)
        }
    }

    // A jar-like blob so the patch path is real (bspatch over a large mostly-stable file).
    private fun jar(v: Int) = ByteArray(120_000) { (it % 251).toByte() }
        .also { for (i in 60_000 until 60_100) it[i] = v.toByte() }

    private fun setup(): Triple<Path, Path, Path> {
        val base = Files.createTempDirectory("engine")
        val live = base.resolve("live")   // the OLD install
        val server = base.resolve("new")  // the NEW release layout
        // OLD install
        Files.createDirectories(live)
        Files.write(live.resolve("lib/nexira.jar").also { it.createParentDirectories() }, jar(1))
        write(live, "runtime/x", "rt")            // unchanged
        write(live, "agents/gone.jar", "bye")     // removed by new
        // NEW release
        Files.createDirectories(server)
        Files.write(server.resolve("lib/nexira.jar").also { it.createParentDirectories() }, jar(2))
        write(server, "runtime/x", "rt")
        write(server, "natives/new.so", "brand new native")
        return Triple(base, live, server)
    }

    @Test
    fun fullUpdatePatchesDownloadsAndDeletes() {
        val (base, live, server) = setup()
        try {
            val layout = InstallLayout(live)
            val local = LayoutManifest.scan(live, excludes = setOf(layout.stagingDir))
            val remote = LayoutManifest.scan(server)
            val jarSha = { m: FileManifest -> m.flatten().getValue("lib/nexira.jar").sha1 }

            // Precompute the jar patch old->new.
            val patchFile = base.resolve("nexira.patch")
            BinaryPatch.diff(live.resolve("lib/nexira.jar"), server.resolve("lib/nexira.jar"), patchFile)
            val patch = LauncherPatch("lib/nexira.jar", fromSha1 = jarSha(local), toSha1 = jarSha(remote))

            val plan = LauncherUpdatePlanner.plan(local, remote, mapOf("lib/nexira.jar" to patch))
            assertEquals(listOf("lib/nexira.jar"), plan.patches.map { it.path })
            assertEquals(listOf("natives/new.so"), plan.downloads.map { it.path })
            assertEquals(listOf("agents/gone.jar"), plan.deletes.map { it.path })

            val source = FakeSource(server, mapOf("lib/nexira.jar" to patchFile))
            val staged = UpdateStager(layout, source).stage(plan, remote)
            LayoutApplier(layout).apply(staged, remote, "2.0.0")

            // Live layout now matches the new release, content-wise.
            assertContentEqualsBytes(server.resolve("lib/nexira.jar"), live.resolve("lib/nexira.jar"))
            assertEquals("brand new native", read(live.resolve("natives/new.so")))
            assertEquals("rt", read(live.resolve("runtime/x")))          // untouched
            assertFalse(Files.exists(live.resolve("agents/gone.jar")))   // deleted
            assertEquals("2.0.0", read(layout.versionFile))
            // Recorded manifest is the new one; staging cleaned.
            assertEquals(remote.flatten(), LayoutManifest.read(layout.manifestFile)?.flatten())
            assertTrue(Files.list(layout.stagingDir).use { it.count() } == 0L)
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun verifyFailureAbortsWithoutTouchingLiveLayout() {
        val (base, live, server) = setup()
        try {
            val layout = InstallLayout(live)
            val local = LayoutManifest.scan(live, excludes = setOf(layout.stagingDir))
            val remote = LayoutManifest.scan(server)
            val plan = LauncherUpdatePlanner.plan(local, remote) // no patches -> jar is a full download

            // Corrupt the served jar so its sha256 will not match the remote manifest.
            val corruptServer = base.resolve("corrupt"); Files.createDirectories(corruptServer)
            Files.copy(server.resolve("runtime/x").also {}, corruptServer.resolve("runtime/x").also { it.createParentDirectories() })
            Files.write(corruptServer.resolve("lib/nexira.jar").also { it.createParentDirectories() }, jar(9)) // wrong bytes
            Files.write(corruptServer.resolve("natives/new.so").also { it.createParentDirectories() }, "brand new native".toByteArray())

            val source = FakeSource(corruptServer, emptyMap())
            val jarBefore = Files.readAllBytes(live.resolve("lib/nexira.jar"))

            assertFailsWith<UpdateVerifyException> { UpdateStager(layout, source).stage(plan, remote) }
            // Live jar is exactly as it was -- nothing applied.
            assertContentEquals(jarBefore, Files.readAllBytes(live.resolve("lib/nexira.jar")))
            assertTrue(Files.exists(live.resolve("agents/gone.jar")))
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun recoverResumesAnInterruptedApply() {
        val (base, live, server) = setup()
        try {
            val layout = InstallLayout(live)
            val local = LayoutManifest.scan(live, excludes = setOf(layout.stagingDir))
            val remote = LayoutManifest.scan(server)
            val plan = LauncherUpdatePlanner.plan(local, remote) // full downloads
            val source = FakeSource(server, emptyMap())

            // Stage everything, then simulate a crash AFTER the commit marker was written
            // but BEFORE the moves ran: write the marker by hand, leave staging intact.
            val staged = UpdateStager(layout, source).stage(plan, remote)
            val marker = layout.stagingDir.resolve(".commit.json")
            val commit = ApplyCommit("2.0.0", staged.staged.keys.toList(), staged.deletes)
            AtomicFiles.writeString(marker, Json { encodeDefaults = true }.encodeToString(ApplyCommit.serializer(), commit))

            // Fresh applier (new process) recovers.
            LayoutApplier(layout).recover { v -> if (v == "2.0.0") remote else null }

            assertContentEqualsBytes(server.resolve("lib/nexira.jar"), live.resolve("lib/nexira.jar"))
            assertEquals("brand new native", read(live.resolve("natives/new.so")))
            assertFalse(Files.exists(live.resolve("agents/gone.jar")))
            assertEquals("2.0.0", read(layout.versionFile))
            assertFalse(Files.exists(marker))
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun aotCacheIsInvalidatedWhenTheJarChanges() {
        val (base, live, server) = setup()
        try {
            val layout = InstallLayout(live)
            Files.write(layout.aotCache, "stale-cache".toByteArray())
            val local = LayoutManifest.scan(live, excludes = layout.bookkeeping)
            val remote = LayoutManifest.scan(server, excludes = layout.bookkeeping)
            val plan = LauncherUpdatePlanner.plan(local, remote) // jar changed -> full download
            val staged = UpdateStager(layout, FakeSource(server, emptyMap())).stage(plan, remote)
            LayoutApplier(layout).apply(staged, remote, "2.0.0")
            assertFalse(Files.exists(layout.aotCache), "stale AOT cache should be dropped after a jar update")
        } finally {
            deleteTree(base)
        }
    }

    @Test
    fun aotCacheSurvivesAnUpdateThatDoesNotTouchTheJar() {
        val base = Files.createTempDirectory("engine")
        try {
            val live = base.resolve("live"); Files.createDirectories(live)
            Files.write(live.resolve("lib/nexira.jar").also { it.createParentDirectories() }, jar(1))
            val layout = InstallLayout(live)
            Files.write(layout.aotCache, "keep-cache".toByteArray())

            val server = base.resolve("new"); Files.createDirectories(server)
            Files.write(server.resolve("lib/nexira.jar").also { it.createParentDirectories() }, jar(1)) // unchanged
            write(server, "natives/added", "n")

            val local = LayoutManifest.scan(live, excludes = layout.bookkeeping)
            val remote = LayoutManifest.scan(server, excludes = layout.bookkeeping)
            val plan = LauncherUpdatePlanner.plan(local, remote) // only the added native
            val staged = UpdateStager(layout, FakeSource(server, emptyMap())).stage(plan, remote)
            LayoutApplier(layout).apply(staged, remote, "2.0.0")
            assertTrue(Files.exists(layout.aotCache), "AOT cache should be kept when the jar is unchanged")
        } finally {
            deleteTree(base)
        }
    }

    private fun assertContentEqualsBytes(expected: Path, actual: Path) =
        assertContentEquals(Files.readAllBytes(expected), Files.readAllBytes(actual))
}
