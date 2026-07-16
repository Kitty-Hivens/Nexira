package hivens.core.io

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SharedZipTest {

    @Test
    fun `reads an entry over a NIO channel and releases the file on close`() {
        val dir = Files.createTempDirectory("sharedzip")
        val jar = dir.resolve("a.jar")
        ZipOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry("hello.txt"))
            out.write("hi".toByteArray())
            out.closeEntry()
        }

        openSharedZip(jar).use { zip ->
            val entry = zip.getEntry("hello.txt")
            assertNotNull(entry)
            assertEquals("hi", zip.getInputStream(entry).readBytes().decodeToString())
        }

        // The channel closed with the ZipFile, so the archive is no longer held.
        assertEquals(true, Files.deleteIfExists(jar))
    }
}
