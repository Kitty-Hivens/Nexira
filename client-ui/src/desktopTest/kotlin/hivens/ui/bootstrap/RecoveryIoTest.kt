package hivens.ui.bootstrap

import hivens.config.Storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryIoTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-recoveryio-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(dataDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun settings() = dataDir / Storage.SETTINGS_FILE
    private fun readJson() = Json.parseToJsonElement(Files.readString(settings())).jsonObject

    @Test
    fun `writeDisabledModules preserves sibling keys`() {
        Files.writeString(settings(), """{ "memoryMB": 8192, "locale": "de" }""")
        RecoveryIo.writeDisabledModules(dataDir, setOf("skinema", "tray"))

        val root = readJson()
        assertEquals(8192, root["memoryMB"]?.jsonPrimitive?.intOrNull, "sibling keys must survive the write")
        assertEquals("de", root["locale"]?.jsonPrimitive?.contentOrNull)
        assertEquals(setOf("skinema", "tray"), RecoveryIo.readDisabledModules(dataDir))
    }

    @Test
    fun `resetSettings drops other keys but keeps disabledModules`() {
        Files.writeString(settings(), """{ "memoryMB": 8192, "disabledModules": ["keyring"] }""")
        RecoveryIo.resetSettings(dataDir)

        val root = readJson()
        assertFalse(root.containsKey("memoryMB"), "reset must drop other settings to defaults")
        assertEquals(setOf("keyring"), RecoveryIo.readDisabledModules(dataDir), "a reset must not re-enable a disabled module")
    }

    @Test
    fun `resetLayout deletes the layout graph`() {
        val layout = dataDir / Storage.LAYOUT_GRAPH_FILE
        Files.writeString(layout, "{}")
        RecoveryIo.resetLayout(dataDir)
        assertFalse(Files.exists(layout))
    }

    @Test
    fun `readDisabledModules is empty on a clean install`() {
        assertTrue(RecoveryIo.readDisabledModules(dataDir).isEmpty())
    }
}
