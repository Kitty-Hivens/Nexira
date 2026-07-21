package hivens.launcher.update

import hivens.core.data.flatten
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LayoutManifestTest {

    private fun write(root: Path, rel: String, content: String): Path {
        val p = root.resolve(rel)
        p.createParentDirectories()
        Files.writeString(p, content)
        return p
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun scanHashesFilesRelativeAndExcludesStaging() {
        val root = Files.createTempDirectory("layout")
        try {
            write(root, "lib/nexira.jar", "jarbytes")     // 8 bytes
            write(root, "runtime/bin/java", "javabytes")
            write(root, "staging/tmp.bin", "junk")        // excluded

            val m = LayoutManifest.scan(root, excludes = setOf(root.resolve("staging")))
            val flat = m.flatten()

            assertEquals(setOf("lib/nexira.jar", "runtime/bin/java"), flat.keys)
            assertFalse(flat.keys.any { it.startsWith("staging") })
            val jar = flat.getValue("lib/nexira.jar")
            assertEquals(8L, jar.size)
            assertTrue(jar.sha1.isNotEmpty())
            assertTrue(jar.sha256.isNotEmpty())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun hashesMatchKnownVectors() {
        val root = Files.createTempDirectory("layout")
        try {
            write(root, "f", "abc")
            val d = LayoutManifest.scan(root).flatten().getValue("f")
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", d.sha1)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", d.sha256)
            assertEquals(3L, d.size)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun manifestRoundTripsThroughDisk() {
        val root = Files.createTempDirectory("layout")
        try {
            write(root, "lib/nexira.jar", "a")
            write(root, "runtime/x", "bb")
            write(root, "agents/y.jar", "ccc")
            val scanned = LayoutManifest.scan(root)

            val out = root.resolve("manifest.json")
            LayoutManifest.write(out, scanned)
            val back = LayoutManifest.read(out)

            assertEquals(scanned.flatten(), back?.flatten())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun readMissingManifestIsNull() {
        val root = Files.createTempDirectory("layout")
        try {
            assertEquals(null, LayoutManifest.read(root.resolve("nope.json")))
        } finally {
            deleteTree(root)
        }
    }
}
