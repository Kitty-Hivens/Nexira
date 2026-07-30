package hivens.core.util

import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertFailsWith
import java.io.IOException
import hivens.core.io.UnpackLimits
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

    @Test
    fun `unzip refuses an entry whose name the caller reserved`() {
        // The extra.zip unpack keeps its prune index in the same directory it
        // unpacks into. An archive shipping that name would otherwise choose
        // what the next sync deletes.
        val zip = File(workDir, "reserved.zip")
        ZipArchiveOutputStream(FileOutputStream(zip)).use { zos ->
            zos.putArchiveEntry(ZipArchiveEntry(".extra_unpacked_index.json"))
            zos.write("""{"hash":"","paths":["mods/victim.jar"]}""".toByteArray())
            zos.closeArchiveEntry()
            zos.putArchiveEntry(ZipArchiveEntry("mods/real.jar"))
            zos.write("jar".toByteArray())
            zos.closeArchiveEntry()
        }

        val dest = File(workDir, "reserved-dest").also { it.mkdirs() }
        val ours = File(dest, ".extra_unpacked_index.json").also { it.writeText("ours") }

        val extracted = ZipUtils.unzip(zip, dest, reserved = setOf(".extra_unpacked_index.json"))

        assertEquals(listOf("mods/real.jar"), extracted, "the reserved entry must not be reported as extracted")
        assertEquals("ours", ours.readText(), "the reserved file must not be overwritten")
    }

    @Test
    fun `an archive that unpacks past the budget is stopped`() {
        // Driven through a small limit rather than a real bomb: producing four
        // gigabytes to trip the shipped cap would cost a CI runner four
        // gigabytes of writes to prove a counter works.
        val zip = File(workDir, "bomb.zip")
        ZipArchiveOutputStream(FileOutputStream(zip)).use { zos ->
            repeat(4) { i ->
                zos.putArchiveEntry(ZipArchiveEntry("filler-$i.bin"))
                zos.write(ByteArray(32 * 1024))
                zos.closeArchiveEntry()
            }
        }

        val dest = File(workDir, "bomb-dest").also { it.mkdirs() }
        assertFailsWith<IOException> {
            ZipUtils.unzip(zip, dest, limits = UnpackLimits(maxEntries = 100, maxBytes = 40 * 1024))
        }

        val written = dest.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        assertTrue(written < 64 * 1024, "unpack ran past the limit and wrote $written bytes")
    }

    @Test
    fun `an archive with too many entries is stopped`() {
        val zip = File(workDir, "many.zip")
        ZipArchiveOutputStream(FileOutputStream(zip)).use { zos ->
            repeat(10) { i ->
                zos.putArchiveEntry(ZipArchiveEntry("f-$i.txt"))
                zos.write("x".toByteArray())
                zos.closeArchiveEntry()
            }
        }

        val dest = File(workDir, "many-dest").also { it.mkdirs() }
        assertFailsWith<IOException> {
            ZipUtils.unzip(zip, dest, limits = UnpackLimits(maxEntries = 3, maxBytes = 1024 * 1024))
        }
    }
}
