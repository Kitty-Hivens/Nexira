package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.i18n.I18nSettingsDomain
import hivens.ui.i18n.NexiraStrings
import hivens.ui.settings.SettingsDomainFiles
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The window the launcher boots in and the shell after it speak the same language, read from the same place. */
class BootLanguageTest {

    private val dataDir: Path = Files.createTempDirectory("boot-language")

    @AfterTest
    fun tearDown() {
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `the boot window and the shell agree when only the language's own file holds it`() {
        dataDir.resolve(Storage.SETTINGS_DIR).createDirectories().resolve("i18n.json").writeText("""{"schema":1,"value":{"language":"de"}}""")
        val translations = dataDir.resolve(Storage.TRANSLATIONS_DIR)

        val boot = NexiraStrings.engine(translations, SettingsPeek.read(dataDir).locale).current.value.language.tag
        val stored = SettingsDomainFiles(dataDir.resolve(Storage.SETTINGS_DIR), dataDir.resolve(Storage.SETTINGS_FILE)).of(I18nSettingsDomain)
        val shell = NexiraStrings.engine(translations, stored.value.value.language).current.value.language.tag

        assertEquals("de", boot)
        assertEquals(boot, shell)
    }
}
