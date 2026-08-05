package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.data.SettingsData
import hivens.core.data.ThemeMode
import hivens.core.data.resolveInitialThemeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.div
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FirstRunDefaultsTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-firstrun-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(dataDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun settings() = dataDir / Storage.SETTINGS_FILE
    private val json = Json { ignoreUnknownKeys = true }
    private fun readSettings() = json.decodeFromString<SettingsData>(Files.readString(settings()))

    @Test
    fun `a translated system language wins, regardless of region`() {
        assertEquals("ru", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("ru-RU")))
        assertEquals("ru", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("ru-UA")))
        assertEquals("ru", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("ru-KZ")))
        assertEquals("de", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("de-CH")))
        assertEquals("en", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun `an untranslated system language falls back to English`() {
        assertEquals("en", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("pl-PL")))
        assertEquals("en", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("uk-UA")))
        assertEquals("en", FirstRunDefaults.supportedLocaleTag(Locale.forLanguageTag("zh-CN")))
        assertEquals("en", FirstRunDefaults.supportedLocaleTag(Locale.ROOT))
    }

    @Test
    fun `a first launch is dark and stays dark on a light desktop`() {
        val seeded = FirstRunDefaults.firstRunSettings(Locale.forLanguageTag("ru-RU"))
        assertTrue(seeded.isDarkTheme)
        // System mode is what hands the choice to the OS scheme; anything else
        // keeps the seeded value until the user picks a mode themselves.
        assertNotEquals(ThemeMode.System, resolveInitialThemeMode(seeded))
    }

    @Test
    fun `seeding writes the system language into a clean install`() {
        FirstRunDefaults.seed(dataDir, Locale.forLanguageTag("ru-RU"))

        val settings = readSettings()
        assertEquals("ru", settings.locale)
        assertTrue(settings.isDarkTheme)
        assertNotEquals(ThemeMode.System, resolveInitialThemeMode(settings))
    }

    @Test
    fun `the boot window reads the seeded language`() {
        FirstRunDefaults.seed(dataDir, Locale.forLanguageTag("de-DE"))

        // SettingsPeek is the pre-Koin read the boot threshold renders from --
        // seeding after it would leave the first window English on a German box.
        val peek = SettingsPeek.read(dataDir)
        assertEquals("de", peek.locale)
        assertTrue(peek.isDarkTheme)
    }

    @Test
    fun `an existing settings file is never touched`() {
        val existing = """{ "locale": "en", "isDarkTheme": false, "memoryMB": 8192 }"""
        Files.writeString(settings(), existing)

        FirstRunDefaults.seed(dataDir, Locale.forLanguageTag("ru-RU"))

        assertEquals(existing, Files.readString(settings()), "a second launch must not re-decide settled values")
    }

    @Test
    fun `only the keys that differ from the shipped defaults are written`() {
        FirstRunDefaults.seed(dataDir, Locale.forLanguageTag("en-US"))

        val keys: Set<String> = Json.parseToJsonElement(Files.readString(settings())).jsonObject.keys
        // English is already the shipped locale, so nothing pins it -- an install
        // that never opens the settings screen keeps following the defaults it
        // did not need an opinion about.
        assertFalse("locale" in keys, "a value equal to the shipped default must not be pinned")
        assertFalse("memoryMB" in keys)
        assertTrue("themeMode" in keys, "the theme mode is the one thing a first run does decide")
    }
}
