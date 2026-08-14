package hivens.launcher

import hivens.core.data.ModuleId
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
 * #189 -- SettingsService is read by Compose UI threads (settings screen
 * recomposition) and IO coroutines (startup load, override restore).
 * Without coordination two concurrent saves could race the file
 * write and the UI could observe a half-applied SettingsData.
 */
class SettingsServiceTest {

    private lateinit var workDir: Path
    // Mirror the production Json config (see networkModule in
    // hivens.launcher.di.Modules). coerceInputValues is what protects
    // against the downgrade-after-new-enum-variant scenario covered
    // below; the test pins the behavior so a future Json refactor
    // cannot quietly strip the flag.
    private val json = Json {
        encodeDefaults    = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @BeforeTest
    fun setup() {
        workDir = Files.createTempDirectory("aura-settings-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(workDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
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

        svc.saveSettings(SettingsData(javaPath = "/opt/jdk/bin/java", locale = "de"))

        // Fresh instance reads the persisted state.
        val reloaded = SettingsService(json, file)
        assertEquals("/opt/jdk/bin/java", reloaded.getSettings().javaPath)
        assertEquals("de", reloaded.getSettings().locale)
    }

    @Test
    fun `unknown enum value coerces to default and preserves other fields`() {
        // Scenario: launcher A writes settings with a new enum variant
        // (HomeView.Future, say); launcher B (older binary, no Future
        // variant) reads the same file. Without coerceInputValues this
        // crashes reload(), which then silently resets EVERY OTHER
        // field to defaults -- the user loses java path, memory, locale,
        // etc. because of one unknown enum string.
        val file = workDir / "settings.json"
        Files.writeString(
            file,
            """
            {
              "javaPath": "/opt/jdk/bin/java",
              "locale": "de",
              "homeView": "Future"
            }
            """.trimIndent(),
        )

        val svc = SettingsService(json, file)
        val loaded = svc.getSettings()

        assertEquals("/opt/jdk/bin/java", loaded.javaPath, "non-enum fields must survive the coercion")
        assertEquals("de", loaded.locale, "non-enum fields must survive the coercion")
        assertEquals(
            SettingsData().homeView,
            loaded.homeView,
            "unknown enum value must coerce to the field default",
        )
    }

    @Test
    fun `disabledModules round-trips through the file`() {
        val file = workDir / "settings.json"
        SettingsService(json, file).saveSettings(
            SettingsData(disabledModules = setOf(ModuleId.Keyring.id, ModuleId.Tray.id)),
        )
        val reloaded = SettingsService(json, file).getSettings()
        assertEquals(setOf("keyring", "tray"), reloaded.disabledModules)
    }

    @Test
    fun `an unknown module id survives decode without resetting other fields`() {
        // disabledModules is Set<String>, not Set<ModuleId>, exactly so a newer
        // build's id (or a typo) read by this build stays an inert string rather
        // than tripping the coerce-to-default reset an enum-in-collection would --
        // the same class of silent wipe the unknown-enum test above guards.
        val file = workDir / "settings.json"
        Files.writeString(
            file,
            """
            {
              "javaPath": "/opt/jdk/bin/java",
              "disabledModules": ["keyring", "future-module"]
            }
            """.trimIndent(),
        )
        val loaded = SettingsService(json, file).getSettings()
        assertEquals("/opt/jdk/bin/java", loaded.javaPath, "sibling fields must survive an unknown module id")
        assertEquals(setOf("keyring", "future-module"), loaded.disabledModules)
        assertEquals(ModuleId.Keyring, ModuleId.fromId("keyring"))
        assertEquals(null, ModuleId.fromId("future-module"), "unknown id maps to no module")
    }

    /**
     * The in-process lock below serialises this launcher's own writers. It says
     * nothing about what the FILE looks like mid-write, and that is what matters
     * on a crash or a full disk: `reload` cannot tell truncated JSON from absent
     * JSON, so a half-written settings.json comes back as defaults and the user
     * loses every setting with nothing in the UI to say so.
     *
     * A reader that never holds the lock -- the next boot, a second instance, a
     * backup -- must only ever see a whole file.
     */
    @Test
    fun `a reader outside the lock never catches settings mid-write`(): Unit = runBlocking {
        val file = workDir / "settings.json"
        val svc = SettingsService(json, file)
        // Big enough that a single non-atomic write cannot land in one go, so a
        // direct write would be caught truncated rather than passing by luck.
        val bulk = (1..4000).map { "module-$it" }.toSet()
        svc.saveSettings(SettingsData(javaPath = "/j/2048", disabledModules = bulk))

        var reads = 0
        coroutineScope {
            val writer = async(Dispatchers.IO) {
                repeat(60) { i -> svc.saveSettings(SettingsData(javaPath = "/j/$i", disabledModules = bulk)) }
            }
            val reader = async(Dispatchers.IO) {
                while (writer.isActive) {
                    val text = runCatching { Files.readString(file) }.getOrNull() ?: continue
                    reads++
                    // Never empty and never partial: the file is published whole or
                    // not at all.
                    assertTrue(text.isNotEmpty(), "settings.json was observed truncated to nothing")
                    json.decodeFromString<SettingsData>(text)
                }
            }
            writer.await()
            reader.await()
        }
        assertTrue(reads > 0, "the reader never got a look in -- the test proves nothing")
    }

    @Test
    fun `concurrent reads and writes never observe a torn SettingsData`(): Unit = runBlocking {
        val svc = SettingsService(json, workDir / "settings.json")

        // Pound the cache with 200 concurrent get/save pairs across IO
        // threads. With the lock in place every getSettings() must return
        // a SettingsData that's internally consistent (every field reflects
        // the same write); without it, two concurrent saves could race to
        // write to the file leaving a partial JSON on disk that getSettings
        // would then attempt to parse on next reload.
        coroutineScope {
            (1..200).map { i ->
                async(Dispatchers.IO) {
                    svc.saveSettings(SettingsData(javaPath = "/j/${i * 16}"))
                    val read = svc.getSettings()
                    // javaPath is the only thing varied -- verify it is a value we
                    // actually wrote (not a ghost composite of two writes).
                    val written = read.javaPath?.removePrefix("/j/")?.toIntOrNull()
                    assertTrue(
                        written != null && written % 16 == 0,
                        "torn write detected: ${read.javaPath}",
                    )
                }
            }.awaitAll()
        }

        // File on disk must be valid JSON regardless of the racing.
        val finalText = withContext(Dispatchers.IO) { Files.readString(workDir / "settings.json") }
        json.decodeFromString<SettingsData>(finalText)  // throws if torn
    }
}
