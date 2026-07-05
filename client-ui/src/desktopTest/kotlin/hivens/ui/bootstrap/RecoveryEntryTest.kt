package hivens.ui.bootstrap

import hivens.config.Storage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryEntryTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-recovery-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(dataDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `no signal means no recovery`() {
        assertFalse(RecoveryEntry.resolve(dataDir, emptyArray()))
    }

    @Test
    fun `the --recovery argument enters recovery`() {
        assertTrue(RecoveryEntry.resolve(dataDir, arrayOf("--recovery")))
        assertTrue(RecoveryEntry.resolve(dataDir, arrayOf("foo", "--recovery", "bar")))
    }

    @Test
    fun `the marker enters recovery once and is then consumed`() {
        val marker = dataDir / Storage.RECOVERY_REQUEST_FILE
        Files.writeString(marker, "")

        assertTrue(RecoveryEntry.resolve(dataDir, emptyArray()), "marker present -> recovery")
        assertFalse(Files.exists(marker), "marker must be deleted after it fires")
        assertFalse(RecoveryEntry.resolve(dataDir, emptyArray()), "one-shot: no recovery on the next boot")
    }
}
