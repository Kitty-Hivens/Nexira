package hivens.ui.bootstrap

import hivens.config.Storage
import hivens.core.io.readUtf8
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Direct pre-Koin read of what the window host needs of the launcher's own settings at
 * window-creation time: `undecorated` is fixed for the window's lifetime, since flipping it later
 * recreates the AWT peer, a visible flash.
 *
 * Defaults MUST mirror SettingsData's. A missing file (fresh install) or a failed parse has to
 * produce the same window the settings service would.
 *
 * The language the boot window speaks and the theme it is drawn in are not here: they are the
 * modules', which answer them before the host is up (see [bootValues]) from the same files their
 * keys answer them from once it is.
 */
data class SettingsPeek(
    val useCustomChrome: Boolean = true,
) {
    companion object {
        fun read(dataDir: Path): SettingsPeek = runCatching {
            val file = dataDir.resolve(Storage.SETTINGS_FILE)
            if (!Files.isRegularFile(file)) return@runCatching SettingsPeek()
            val root = Json.parseToJsonElement(readUtf8(file)).jsonObject
            SettingsPeek(useCustomChrome = root["useCustomChrome"]?.jsonPrimitive?.booleanOrNull ?: true)
        }.getOrDefault(SettingsPeek())
    }
}
