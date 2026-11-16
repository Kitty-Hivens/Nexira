package hivens.launcher.bootstrap

import hivens.launcher.platform.PlatformPaths
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogDirectoryTest {

    private val saved = listOf(LogDirectory.LOGS_DIR_PROPERTY, LogDirectory.SESSION_ID_PROPERTY)
        .associateWith { System.getProperty(it) }

    @BeforeTest
    fun clear() = saved.keys.forEach { System.clearProperty(it) }

    @AfterTest
    fun restore() = saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }

    private fun pathsIn(dataDir: Path) =
        PlatformPaths(osName = "Linux", home = dataDir, bootstrapDataDir = { null }, env = { if (it == "NEXIRA_DATA_DIR") dataDir.toString() else null })

    @Test
    fun `the log directory is the logs folder of the data directory`() {
        val dataDir = createTempDirectory("log-directory")

        LogDirectory.claim(pathsIn(dataDir))

        assertEquals(dataDir.resolve("logs").toString(), System.getProperty(LogDirectory.LOGS_DIR_PROPERTY))
    }

    @Test
    fun `the session id the entry point made is the one the boot pipeline gets back`() {
        // The entry names the directory first and the boot pipeline names it again, and a line
        // logged between the two must carry the same session as every line after.
        val dataDir = createTempDirectory("log-directory")

        val atEntry = LogDirectory.claim(pathsIn(dataDir))
        val atBoot = LogDirectory.claim(pathsIn(dataDir))

        assertEquals(atEntry, atBoot)
        assertEquals(atEntry, System.getProperty(LogDirectory.SESSION_ID_PROPERTY))
        assertEquals(8, atEntry.length)
    }
}
