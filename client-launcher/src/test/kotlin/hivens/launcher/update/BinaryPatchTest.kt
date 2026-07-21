package hivens.launcher.update

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class BinaryPatchTest {

    private fun deleteTree(root: Path) {
        Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    @Test
    fun roundTripReconstructsNewFromOldPlusPatch() {
        val dir = Files.createTempDirectory("patch")
        try {
            // A jar-like blob: large and mostly stable between versions.
            val old = ByteArray(300_000) { (it % 251).toByte() }
            val new = old.copyOf().also { for (i in 150_000 until 150_120) it[i] = 0x7F }

            val oldF = dir.resolve("old"); Files.write(oldF, old)
            val newF = dir.resolve("new"); Files.write(newF, new)
            val patchF = dir.resolve("delta"); BinaryPatch.diff(oldF, newF, patchF)
            val outF = dir.resolve("out"); BinaryPatch.apply(oldF, patchF, outF)

            assertContentEquals(new, Files.readAllBytes(outF))
            // The delta is a small fraction of the file, not the whole thing.
            assertTrue(
                Files.size(patchF) < Files.size(newF) / 4,
                "expected a small delta, got ${Files.size(patchF)} of ${Files.size(newF)}",
            )
        } finally {
            deleteTree(dir)
        }
    }

    @Test
    fun identicalFilesProduceATinyPatch() {
        val dir = Files.createTempDirectory("patch")
        try {
            val bytes = ByteArray(100_000) { (it % 97).toByte() }
            val a = dir.resolve("a"); Files.write(a, bytes)
            val b = dir.resolve("b"); Files.write(b, bytes)
            val patchF = dir.resolve("delta"); BinaryPatch.diff(a, b, patchF)
            val outF = dir.resolve("out"); BinaryPatch.apply(a, patchF, outF)

            assertContentEquals(bytes, Files.readAllBytes(outF))
            assertTrue(Files.size(patchF) < 1_000, "no-change delta should be tiny, got ${Files.size(patchF)}")
        } finally {
            deleteTree(dir)
        }
    }
}
