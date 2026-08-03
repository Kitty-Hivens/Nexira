package hivens.update

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createParentDirectories
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeltaBundleTest {

    private fun deleteTree(root: Path) {
        if (Files.exists(root)) Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
    private fun bin(root: Path, rel: String, bytes: ByteArray) {
        val p = root.resolve(rel); p.createParentDirectories(); Files.write(p, bytes)
    }
    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { s ->
            s.forEach { src ->
                val dst = to.resolve(from.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dst)
                else { dst.createParentDirectories(); Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING) }
            }
        }
    }
    private fun jar(v: Int) = ByteArray(120_000) { (it % 251).toByte() }.also { for (i in 60_000 until 60_120) it[i] = v.toByte() }

    @Test
    fun produceThenApplyMovesTheInstallToTheNewRelease() {
        val base = Files.createTempDirectory("bundle")
        try {
            val old = base.resolve("old")
            bin(old, "lib/nexira.jar", jar(1))
            bin(old, "runtime/x", "rt".toByteArray())
            bin(old, "agents/gone.jar", "bye".toByteArray())

            val new = base.resolve("new")
            bin(new, "lib/nexira.jar", jar(2))       // changed -> patch
            bin(new, "runtime/x", "rt".toByteArray()) // unchanged -> nothing
            bin(new, "natives/new.so", "brand new".toByteArray()) // added -> full
            // gone.jar removed

            // CI produces the delta bundle for the step old -> new.
            val bundleDir = base.resolve("delta")
            DeltaBundles.produce(old, new, bundleDir)

            // Bundle shape: patch for the changed jar, full copy for the added native,
            // nothing whole for the changed jar, no patch for the added file.
            assertTrue(Files.exists(bundleDir.resolve("files.json")))
            assertTrue(Files.exists(bundleDir.resolve("patches/lib/nexira.jar.bsdiff")))
            assertTrue(Files.exists(bundleDir.resolve("full/natives/new.so")))
            assertFalse(Files.exists(bundleDir.resolve("full/lib/nexira.jar")))
            assertFalse(Files.exists(bundleDir.resolve("patches/natives/new.so.bsdiff")))

            // A live install sitting at the old version.
            val live = base.resolve("live")
            copyTree(old, live)
            val layout = InstallLayout(live)
            LayoutManifest.write(layout.manifestFile, LayoutManifest.scan(live, excludes = setOf(layout.stagingDir)))

            // The client reads the bundle and applies it.
            val bundle = DeltaBundles.read(bundleDir)
            val outcome = LauncherUpdater(layout).update(bundle.manifest, bundle.patches, bundle.source, "2.0.0")

            assertIs<UpdateOutcome.Applied>(outcome)
            assertContentEquals(jar(2), Files.readAllBytes(live.resolve("lib/nexira.jar")))
            assertEquals("brand new", Files.readString(live.resolve("natives/new.so")))
            assertEquals("rt", Files.readString(live.resolve("runtime/x")))
            assertFalse(Files.exists(live.resolve("agents/gone.jar")))
            assertEquals("2.0.0", Files.readString(layout.versionFile))
        } finally {
            deleteTree(base)
        }
    }
}
