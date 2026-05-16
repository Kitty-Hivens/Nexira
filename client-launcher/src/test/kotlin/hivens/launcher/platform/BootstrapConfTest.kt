package hivens.launcher.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootstrapConfTest {

    private lateinit var workDir: Path
    private lateinit var confFile: Path

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-bootstrap-test-")
        confFile = workDir / ".aura-launcher.conf"
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `read on missing file -- returns empty map, doesn't throw`() {
        assertEquals(emptyMap(), BootstrapConf.read(confFile))
    }

    @Test
    fun `write then read -- round-trips known keys`() {
        BootstrapConf.write(mapOf(
            BootstrapConf.KEY_DATA_DIR to "/tmp/custom-aura-data",
        ), confFile)

        val read = BootstrapConf.read(confFile)
        assertEquals("/tmp/custom-aura-data", read[BootstrapConf.KEY_DATA_DIR])
    }

    @Test
    fun `write skips blank values`() {
        BootstrapConf.write(mapOf(
            BootstrapConf.KEY_DATA_DIR to "/tmp/data",
            BootstrapConf.KEY_PENDING_SOURCE to "",
            BootstrapConf.KEY_PENDING_TARGET to "   ",
        ), confFile)

        val read = BootstrapConf.read(confFile)
        assertEquals(1, read.size, "blank values must not be persisted")
        assertEquals("/tmp/data", read[BootstrapConf.KEY_DATA_DIR])
    }

    @Test
    fun `malformed lines are silently ignored`() {
        Files.writeString(confFile, """
            data-dir=/tmp/valid
            this-line-has-no-equals
            =leading-equals-value
            # comment-style line
            data-dir-pending-source=/tmp/old
        """.trimIndent())

        val read = BootstrapConf.read(confFile)
        assertEquals(2, read.size, "only well-formed key=value lines kept")
        assertEquals("/tmp/valid", read[BootstrapConf.KEY_DATA_DIR])
        assertEquals("/tmp/old", read[BootstrapConf.KEY_PENDING_SOURCE])
    }

    @Test
    fun `update reads, mutates, writes back`() {
        BootstrapConf.write(mapOf(BootstrapConf.KEY_DATA_DIR to "/tmp/v1"), confFile)

        BootstrapConf.update(confFile) { it[BootstrapConf.KEY_DATA_DIR] = "/tmp/v2" }

        assertEquals("/tmp/v2", BootstrapConf.read(confFile)[BootstrapConf.KEY_DATA_DIR])
    }

    @Test
    fun `update can remove keys`() {
        BootstrapConf.write(mapOf(
            BootstrapConf.KEY_DATA_DIR to "/tmp/data",
            BootstrapConf.KEY_PENDING_SOURCE to "/tmp/old",
        ), confFile)

        BootstrapConf.update(confFile) { it.remove(BootstrapConf.KEY_PENDING_SOURCE) }

        val read = BootstrapConf.read(confFile)
        assertEquals("/tmp/data", read[BootstrapConf.KEY_DATA_DIR])
        assertNull(read[BootstrapConf.KEY_PENDING_SOURCE])
    }

    @Test
    fun `write creates parent directory if missing`() {
        val nested = workDir / "sub" / "dir" / ".aura-launcher.conf"
        BootstrapConf.write(mapOf(BootstrapConf.KEY_DATA_DIR to "/tmp/x"), nested)
        assertTrue(Files.exists(nested))
        assertEquals("/tmp/x", BootstrapConf.read(nested)[BootstrapConf.KEY_DATA_DIR])
    }
}
