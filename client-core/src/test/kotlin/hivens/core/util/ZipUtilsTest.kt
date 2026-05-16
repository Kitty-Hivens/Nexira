package hivens.core.util

import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage focuses on the security-relevant edges:
 *   - Zip Slip (`../` escape) -- already protected, regression watchdog.
 *   - Symlink entries -- added in #187 as a separate vector that
 *     plain Zip Slip checks miss.
 *
 * Happy-path extraction is intentionally light here -- the launcher's
 * extra.zip flow exercises that thoroughly through FileDownloadServiceTest.
 */
class ZipUtilsTest {

    private lateinit var workDir: File

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-ziputils-test-").toFile()
    }

    @AfterTest
    fun teardown() {
        workDir.walkBottomUp().forEach { it.delete() }
    }

    @Test
    fun `unzip extracts plain entries and reports relative paths`() {
        val zip = File(workDir, "good.zip")
        ZipArchiveOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putArchiveEntry(ZipArchiveEntry("subdir/inner.txt"))
            zos.write("hello".toByteArray())
            zos.closeArchiveEntry()
        }

        val dest = File(workDir, "dest").also { it.mkdirs() }
        val extracted = ZipUtils.unzip(zip, dest)

        assertEquals(listOf("subdir/inner.txt"), extracted)
        assertEquals("hello", File(dest, "subdir/inner.txt").readText())
    }

    @Test
    fun `unzip skips symlink entries (#187)`() {
        // Hostile archive: a symlink entry called inside-payload.txt whose
        // payload is the path of the link target. Plain Zip Slip allows this
        // since the entry name normalizes inside dest -- only the unix-mode
        // type bits (UnixStat.LINK_FLAG = 0xA000) reveal it as a symlink.
        // ZipUtils must drop the entry and continue, NOT write the payload
        // to the resolved path.
        val zip = File(workDir, "evil.zip")
        ZipArchiveOutputStream(FileOutputStream(zip)).use { zos ->
            // Plain file alongside the symlink so we can verify extraction continues.
            zos.putArchiveEntry(ZipArchiveEntry("benign.txt"))
            zos.write("ok".toByteArray())
            zos.closeArchiveEntry()

            val link = ZipArchiveEntry("inside-payload.txt")
            link.unixMode = UnixStat.LINK_FLAG or 0b111_111_111  // 0xA1FF
            zos.putArchiveEntry(link)
            zos.write("/etc/passwd".toByteArray())
            zos.closeArchiveEntry()
        }

        val dest = File(workDir, "dest").also { it.mkdirs() }
        val extracted = ZipUtils.unzip(zip, dest)

        assertEquals(listOf("benign.txt"), extracted, "symlink entry must be excluded from extracted list")
        assertTrue(File(dest, "benign.txt").exists(), "non-symlink entry must extract normally")
        assertFalse(File(dest, "inside-payload.txt").exists(), "symlink entry must NOT be materialized on disk")
    }

    @Test
    fun `unzip skips Zip Slip entries that escape the destination`() {
        val zip = File(workDir, "slip.zip")
        ZipArchiveOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putArchiveEntry(ZipArchiveEntry("../escape.txt"))
            zos.write("escaped".toByteArray())
            zos.closeArchiveEntry()
        }

        val dest = File(workDir, "dest").also { it.mkdirs() }
        val extracted = ZipUtils.unzip(zip, dest)

        assertTrue(extracted.isEmpty(), "Zip Slip entry must be excluded")
        assertFalse(File(workDir, "escape.txt").exists(), "no file must appear in the parent dir")
    }
}
