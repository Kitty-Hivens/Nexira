package hivens.core.io

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrivateFilesTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = Files.createTempDirectory("nexira-private-files-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun posix(): Boolean = dir.fileSystem.supportedFileAttributeViews().contains("posix")

    @Test
    fun `a new file is readable only by its owner`() {
        val file = dir.resolve("credentials.json")
        writeStringOwnerOnly(file, """{"accounts":[]}""")

        assertEquals("""{"accounts":[]}""", Files.readString(file))
        if (!posix()) return
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file),
        )
    }

    @Test
    fun `an existing world-readable file is tightened on the next write`() {
        // The upgrade path: a file written by an earlier build is already on
        // disk at the umask's mode, and attributes only apply at creation.
        val file = dir.resolve("credentials.json")
        Files.writeString(file, "old")
        if (posix()) {
            Files.setPosixFilePermissions(
                file,
                setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ,
                ),
            )
        }

        writeStringOwnerOnly(file, "new")

        assertEquals("new", Files.readString(file))
        if (!posix()) return
        val perms = Files.getPosixFilePermissions(file)
        assertTrue(PosixFilePermission.OTHERS_READ !in perms, "still world-readable")
        assertTrue(PosixFilePermission.GROUP_READ !in perms, "still group-readable")
    }

    @Test
    fun `a missing parent directory is created`() {
        val file = dir.resolve("nested/deeper/credentials.json")
        writeStringOwnerOnly(file, "x")
        assertTrue(Files.exists(file))
    }
}
