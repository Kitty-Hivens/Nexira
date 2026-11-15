package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.io.readUtf8
import hivens.i18n.I18nSettingsDomain
import hivens.ui.settings.SettingsDomainFiles
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
 * user's language, not the OS default.
 *
 * Defaults MUST mirror SettingsData's. A missing file (fresh install) or a failed
 * parse has to produce the same window the settings service would.
 *
 * The language is the language module's settings domain, read exactly as the
 * settings host reads it (see [SettingsDomainFiles]): its own file, or what
 * settings.json's `locale` holds while that file is absent or cannot be read, or
 * was changed there by an older build since the domain last wrote it.
 */
data class SettingsPeek(
    /** The language tag the person chose. */
    val locale: String = "en",
    val useCustomChrome: Boolean = true,
    val isDarkTheme: Boolean = true,
) {
    companion object {
        fun read(dataDir: Path): SettingsPeek {
            val language = runCatching { languageIn(dataDir) }.getOrDefault(SettingsPeek().locale)
            return runCatching {
                val file = dataDir.resolve(Storage.SETTINGS_FILE)
                if (!Files.isRegularFile(file)) return@runCatching SettingsPeek(locale = language)
                val root = Json.parseToJsonElement(readUtf8(file)).jsonObject
                SettingsPeek(
                    locale          = language,
                    useCustomChrome = root["useCustomChrome"]?.jsonPrimitive?.booleanOrNull ?: true,
                    isDarkTheme     = root["isDarkTheme"]?.jsonPrimitive?.booleanOrNull ?: true,
                )
            }.getOrDefault(SettingsPeek(locale = language))
        }

        /** The language the person chose, as the language module keeps it under [dataDir]. */
        fun languageIn(dataDir: Path): String = SettingsDomainFiles(
            dir        = dataDir.resolve(Storage.SETTINGS_DIR),
            legacyFile = dataDir.resolve(Storage.SETTINGS_FILE),
        ).peek(I18nSettingsDomain).language
    }
}
