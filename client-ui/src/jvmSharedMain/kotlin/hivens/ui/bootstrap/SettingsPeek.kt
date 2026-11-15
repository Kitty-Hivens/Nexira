package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.io.readUtf8
import hivens.i18n.I18nSettingsDomain
import hivens.themes.ThemeLibraryFile
import hivens.themes.ThemeManager
import hivens.themes.ThemeOpening
import hivens.themes.Themes
import hivens.themes.ThemesSettingsDomain
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.theme.Theme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Direct pre-Koin read of the settings the window host needs at window-creation
 * time: `undecorated` is fixed for the window's lifetime, since flipping it later
 * recreates the AWT peer, a visible flash. The boot-threshold strings want the
 * user's language, not the OS default, and the boot window the user's theme.
 *
 * Defaults MUST mirror SettingsData's. A missing file (fresh install) or a failed
 * parse has to produce the same window the settings service would.
 *
 * The language is the language module's settings domain, read exactly as the
 * settings host reads it (see [SettingsDomainFiles]): its own file, or what
 * settings.json's `locale` holds while that file is absent or cannot be read, or
 * was changed there by an older build since the domain last wrote it. The theme
 * and whether it is dark are the themes module's domain, read the same way, with
 * the theme file beside it, and worked out by the function the module's manager
 * opens with, so the boot window and the shell after it draw the same theme.
 */
data class SettingsPeek(
    /** The language tag the person chose. */
    val locale: String = "en",
    val useCustomChrome: Boolean = true,
    val isDarkTheme: Boolean = true,
    /** The theme the boot window is drawn in. */
    val theme: Theme = Themes.default,
) {
    companion object {
        fun read(dataDir: Path): SettingsPeek {
            val language = runCatching { languageIn(dataDir) }.getOrDefault(SettingsPeek().locale)
            val look = runCatching { themeIn(dataDir) }.getOrNull()
            val read = SettingsPeek(
                locale      = language,
                isDarkTheme = look?.dark ?: SettingsPeek().isDarkTheme,
                theme       = look?.theme ?: SettingsPeek().theme,
            )
            return runCatching {
                val file = dataDir.resolve(Storage.SETTINGS_FILE)
                if (!Files.isRegularFile(file)) return@runCatching read
                val root = Json.parseToJsonElement(readUtf8(file)).jsonObject
                read.copy(useCustomChrome = root["useCustomChrome"]?.jsonPrimitive?.booleanOrNull ?: true)
            }.getOrDefault(read)
        }

        /** The language the person chose, as the language module keeps it under [dataDir]. */
        fun languageIn(dataDir: Path): String = domains(dataDir).peek(I18nSettingsDomain).language

        /** The theme and the scheme the themes module's manager opens on, from what it keeps under [dataDir]. */
        fun themeIn(dataDir: Path): ThemeOpening = ThemeManager.opening(
            settings = domains(dataDir).peek(ThemesSettingsDomain),
            stored   = ThemeLibraryFile(dataDir) { _, _ -> }.load(),
        )

        private fun domains(dataDir: Path) = SettingsDomainFiles(
            dir        = dataDir.resolve(Storage.SETTINGS_DIR),
            legacyFile = dataDir.resolve(Storage.SETTINGS_FILE),
        )
    }
}
