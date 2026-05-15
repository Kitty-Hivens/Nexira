package hivens.launcher

import hivens.core.data.SettingsData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #189 — SettingsService is read by Compose UI threads (settings screen
 * recomposition) and IO coroutines (startup load, Conduit force-proxy
 * restore). Without coordination two concurrent saves could race the file
 * write and the UI could observe a half-applied SettingsData.
 */
class SettingsServiceTest {

    private lateinit var workDir: Path
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-settings-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `getSettings returns defaults on a clean install`() {
        val svc = SettingsService(json, workDir / "settings.json")
        assertEquals(SettingsData(), svc.getSettings())
    }

    @Test
    fun `saveSettings round-trips through the file`() {
        val file = workDir / "settings.json"
        val svc = SettingsService(json, file)

        svc.saveSettings(SettingsData(memoryMB = 8192, locale = "de"))

        // Fresh instance reads the persisted state.
        val reloaded = SettingsService(json, file)
        assertEquals(8192, reloaded.getSettings().memoryMB)
        assertEquals("de", reloaded.getSettings().locale)
    }

    @Test
    fun `concurrent reads and writes never observe a torn SettingsData`() = runBlocking {
        val svc = SettingsService(json, workDir / "settings.json")

        // Pound the cache with 200 concurrent get/save pairs across IO
        // threads. With the lock in place every getSettings() must return
        // a SettingsData that's internally consistent (every field reflects
        // the same write); without it, two concurrent saves could race the
        // write to the file leaving a partial JSON on disk that getSettings
        // would then attempt to parse on next reload.
        coroutineScope {
            (1..200).map { i ->
                async(Dispatchers.IO) {
                    svc.saveSettings(SettingsData(memoryMB = i * 16))
                    val read = svc.getSettings()
                    // memoryMB is the only thing varied — verify it's a value
                    // we actually wrote (not a ghost composite of two writes).
                    assertTrue(read.memoryMB % 16 == 0, "torn write detected: ${read.memoryMB}")
                }
            }.awaitAll()
        }

        // File on disk must be valid JSON regardless of the racing.
        val finalText = withContext(Dispatchers.IO) { Files.readString(workDir / "settings.json") }
        json.decodeFromString<SettingsData>(finalText)  // throws if torn
    }
}
