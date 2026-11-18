package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.data.SettingsData
import hivens.i18n.I18nSettingsDomain
import hivens.i18n.ShippedLanguages
import hivens.i18n.api.I18nKeys
import hivens.themes.ThemesSettingsDomain
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemesKeys
import hivens.ui.i18n.NexiraStrings
import hivens.ui.settings.SettingsDomainFiles
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A first launch opens on what each module's settings domain says a first start opens on, written into the
 * domain's own file before the boot window asks, and mirrored into the former settings file for an older
 * build, exactly as a change of the domain is.
 */
class FirstRunDefaultsTest {

    private lateinit var dataDir: Path
    private val locale = Locale.getDefault()

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-firstrun-test-")
    }

    @AfterTest
    fun teardown() {
        Locale.setDefault(locale)
        Files.walk(dataDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    private fun settings() = dataDir / Storage.SETTINGS_FILE
    private fun domains() = SettingsDomainFiles(dataDir / Storage.SETTINGS_DIR, settings())
    private val lenient = Json { ignoreUnknownKeys = true }
    private fun readSettings() = lenient.decodeFromString<SettingsData>(Files.readString(settings()))

    /** A first launch on a machine that speaks [tag]. */
    private fun seedOn(tag: String) {
        Locale.setDefault(Locale.forLanguageTag(tag))
        FirstRunDefaults.seed(dataDir)
    }

    @Test
    fun `a first launch is dark and stays dark on a light desktop`() {
        seedOn("ru-RU")
        val themes = domains().peek(ThemesSettingsDomain)
        assertTrue(themes.dark)
        // System mode is what hands the choice to the OS scheme; anything else
        // keeps the seeded value until the user picks a mode themselves.
        assertNotEquals(ThemeMode.System, themes.mode)
    }

    @Test
    fun `seeding writes the system language into a clean install, and mirrors it for an older build`() {
        seedOn("ru-RU")

        assertEquals("ru", domains().peek(I18nSettingsDomain).language)
        val settings = readSettings()
        assertEquals("ru", settings.locale)
        assertTrue(settings.isDarkTheme)
        assertEquals(ThemeMode.Manual.name, settings.themeMode, "a build from before the domains opens the way this one does")
    }

    @Test
    fun `the boot window reads the seeded language`() {
        seedOn("de-DE")

        // What the modules answer before the boot is what the boot threshold renders
        // from, so seeding after it would leave the first window English on a German box.
        val boot = bootValues(dataDir)
        assertEquals("de", boot[I18nKeys.Language]?.tag)
        assertEquals(true, boot[ThemesKeys.Dark])
    }

    @Test
    fun `an existing settings file is never touched`() {
        val existing = """{ "locale": "en", "isDarkTheme": false, "javaPath": "/opt/jdk/bin/java" }"""
        Files.writeString(settings(), existing)

        seedOn("ru-RU")

        assertEquals(existing, Files.readString(settings()), "a second launch must not re-decide settled values")
        assertFalse((dataDir / Storage.SETTINGS_DIR).exists(), "nor write a domain of its own")
    }

    @Test
    fun `only a first start that differs from a domain's defaults is written`() {
        seedOn("en-US")

        // English is already the language's default, so nothing pins it: an install
        // that never opens the settings screen keeps following the defaults it did
        // not need an opinion about.
        assertFalse(domains().fileOf(I18nSettingsDomain).exists(), "a first start equal to the defaults must not be pinned")
        val keys: Set<String> = Json.parseToJsonElement(Files.readString(settings())).jsonObject.keys
        assertFalse("locale" in keys)
        assertFalse("javaPath" in keys)
        assertTrue(domains().fileOf(ThemesSettingsDomain).exists(), "the theme mode is the one thing a first run does decide")
        assertTrue("themeMode" in keys)
    }

    @Test
    fun `the language a first start picks among is the one the launcher's own words ship`() {
        assertEquals(NexiraStrings.languages, ShippedLanguages.tags)
    }

    @Test
    fun `a domain whose first start fails costs only itself`() {
        val dir = dataDir / Storage.SETTINGS_DIR
        FirstRunDefaults.seedDomains(dataDir, compiled = listOf(Probe("broken", Failing), Probe("fine", Fine)))
        assertFalse((dir / "probe.failing.json").exists())
        assertEquals(Note("seeded"), SettingsDomainFiles(dir, settings()).peek(Fine))
    }

    @Serializable
    private data class Note(val text: String = "")

    private object Failing : SettingsDomain<Note>("probe.failing", Note.serializer()) {
        override fun defaults() = Note()
        override fun firstRun(): Note = error("cannot tell")
    }

    private object Fine : SettingsDomain<Note>("probe.fine", Note.serializer()) {
        override fun defaults() = Note()
        override fun firstRun() = Note("seeded")
    }

    /** A compiled module that declares [domain] and nothing else. */
    private class Probe(private val moduleId: String, private val domain: SettingsDomain<Note>) : CompiledModule {
        override val id: String = moduleId
        override val name: String = moduleId
        override val contributions: List<ModuleContribution> = listOf(
            object : KeyContribution {
                override val id: String = "$moduleId.keys"
                override val label: String = moduleId
                override fun declare(keys: ModuleKeys) {
                    keys.settings(domain, SourceKey("$moduleId.settings", ScopeLevel.App), CommandKey<SettingsChange<Note>>("$moduleId.settings.update", ScopeLevel.App))
                }
            },
        )
    }
}
