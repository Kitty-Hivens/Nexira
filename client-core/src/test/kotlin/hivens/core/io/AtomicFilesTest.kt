package hivens.core.io

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicFilesTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("atomic-files-test-")
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `writeString creates missing parent dirs and round-trips`() {
        val file = dir.resolve("a/b/c/data.json")
        AtomicFiles.writeString(file, "hello world")
        assertTrue(Files.isRegularFile(file))
        assertEquals("hello world", Files.readString(file))
    }

    @Test
    fun `writeString overwrites existing content`() {
        val file = dir.resolve("data.json")
        AtomicFiles.writeString(file, "first")
        AtomicFiles.writeString(file, "second")
        assertEquals("second", Files.readString(file))
    }

    @Test
    fun `writeBytes round-trips binary content`() {
        val file = dir.resolve("blob.bin")
        val bytes = byteArrayOf(0, 1, 2, 127, -1, -128, 42)
        AtomicFiles.writeBytes(file, bytes)
        assertContentEquals(bytes, Files.readAllBytes(file))
    }

    @Test
    fun `no orphan tmp file is left after a successful write`() {
        val file = dir.resolve("data.json")
        AtomicFiles.writeString(file, "x")
        assertFalse(Files.exists(file.resolveSibling("data.json.tmp")), "tmp must be moved into place, not left behind")
    }
}
