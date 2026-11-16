package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.data.SettingsData
import hivens.i18n.I18nModule
import hivens.i18n.api.I18nApi
import hivens.i18n.api.I18nKeys
import hivens.i18n.api.Translator
import hivens.test.stopThenDelete
import hivens.themes.ThemesModule
import hivens.themes.api.ThemesApi
import hivens.ui.ModuleAppLevel
import hivens.ui.i18n.NexiraStrings
import hivens.ui.widgets.modules.CompiledModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The window the launcher boots in and the shell after it speak the same language and the same
 * words, read from the same places: what the language module answers before the boot, and what its
 * keys answer once they are up.
 */
class BootLanguageTest {

    private val dataDir: Path = Files.createTempDirectory("boot-language")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dataDir)

    /** The words the boot window speaks. */
    private fun boot(): Translator = bootValues(dataDir)[I18nKeys.Words] ?: error("the language module answered no words before the boot")

    /** The words the shell speaks once the modules' keys are up, over the same files. */
    private fun shell(): Translator =
        ModuleAppLevel(dataDir, CompiledModules, scope, named = setOf(I18nApi.ID, ThemesApi.ID)).use { it.value(I18nKeys.Words) }

    private fun settingsFile() = dataDir.resolve(Storage.SETTINGS_FILE)

    @Test
    fun `the boot window and the shell agree when only the language's own file holds it`() {
        dataDir.resolve(Storage.SETTINGS_DIR).createDirectories().resolve("i18n.json").writeText("""{"schema":1,"value":{"language":"de"}}""")

        assertEquals("de", boot().language.tag)
        assertEquals(boot().language.tag, shell().language.tag)
        assertEquals("de", bootValues(dataDir)[I18nKeys.Language]?.tag, "and the language itself says so")
    }

    @Test
    fun `the language an older build wrote into the settings file is the one both speak`() {
        settingsFile().writeText("""{"locale":"ru","useCustomChrome":false,"isDarkTheme":false,"unrelated":{"nested":1}}""")
        assertEquals("ru", boot().language.tag)
        assertEquals("ru", shell().language.tag)

        val json = Json { encodeDefaults = true }
        settingsFile().writeText(json.encodeToString(SettingsData(locale = "de")))
        assertEquals("de", boot().language.tag, "a persisted SettingsData is read back")
        assertEquals("de", shell().language.tag)
    }

    @Test
    fun `with no settings, or settings that cannot be read, both speak English`() {
        assertEquals("en", boot().language.tag)
        assertEquals("en", shell().language.tag)

        settingsFile().writeText("{not json at all")
        assertEquals("en", boot().language.tag)
        assertEquals("en", shell().language.tag)
    }

    @Test
    fun `a person's own translation is spoken from the first window on`() {
        settingsFile().writeText("""{"locale":"de"}""")
        dataDir.resolve(I18nModule.TRANSLATIONS_DIR).resolve("de").createDirectories()
            .resolve("${NexiraStrings.NAMESPACE}.properties").writeText("navBack=Zurueck, wie ich es mag\n")

        assertEquals("Zurueck, wie ich es mag", boot().strings(NexiraStrings.NAMESPACE).text("navBack"))
        assertEquals("Zurueck, wie ich es mag", shell().strings(NexiraStrings.NAMESPACE).text("navBack"))
        assertEquals(Storage.TRANSLATIONS_DIR, I18nModule.TRANSLATIONS_DIR, "the folder is where the launcher has always kept them")
    }

    @Test
    fun `the launcher's own words and every compiled module's are spoken before the boot as after`() {
        settingsFile().writeText("""{"locale":"ru"}""")
        val before = boot()
        val after = shell()

        assertEquals(after.strings(NexiraStrings.NAMESPACE).text("navBack"), before.strings(NexiraStrings.NAMESPACE).text("navBack"))
        assertEquals("Темы", before.strings(ThemesModule.id).text(ThemesModule.name))
        assertEquals("Темы", after.strings(ThemesModule.id).text(ThemesModule.name))
    }
}
