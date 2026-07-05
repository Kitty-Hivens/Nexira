package hivens.ui.bootstrap

import hivens.config.Storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Direct pre-Koin read of the two settings the window host needs at
 * window-creation time: `undecorated` is fixed for the window's lifetime
 * (flipping it later recreates the AWT peer -- a visible flash), and the
 * boot-threshold strings want the user's locale, not the OS default.
 *
 * Defaults MUST mirror SettingsData's -- a missing file (fresh install) or
 * a failed parse has to produce the same window the settings service would.
 */
data class SettingsPeek(
    val locale: String = "en",
    val useCustomChrome: Boolean = true,
) {
    companion object {
        fun read(dataDir: Path): SettingsPeek = runCatching {
            val file = dataDir.resolve(Storage.SETTINGS_FILE)
            if (!Files.isRegularFile(file)) return@runCatching SettingsPeek()
            val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
            SettingsPeek(
                locale          = root["locale"]?.jsonPrimitive?.contentOrNull ?: "en",
                useCustomChrome = root["useCustomChrome"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }.getOrDefault(SettingsPeek())
    }
}
