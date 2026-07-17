package hivens.core.io

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedZipTest {

    @Test
    fun `reads named entries, lists names, and releases the file on close`() {
        val dir = Files.createTempDirectory("sharedzip")
        val jar = dir.resolve("a.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("hello.txt"))
            out.write("hi".toByteArray())
            out.closeEntry()
            out.putNextEntry(ZipEntry("assets/mod/icon.png"))
            out.write(byteArrayOf(1, 2, 3))
            out.closeEntry()
        }

        openSharedZip(jar).use { zip ->
            assertEquals("hi", zip.readEntry("hello.txt")?.decodeToString())
            assertNull(zip.readEntry("missing.txt"))
            val nested = zip.entryNames().firstOrNull { it.startsWith("assets/") && it.endsWith("/icon.png") }
            assertEquals("assets/mod/icon.png", nested)
        }

        // The reader closed its handle, so the archive is no longer held.
        assertEquals(true, Files.deleteIfExists(jar))
    }
}
