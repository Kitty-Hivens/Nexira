package hivens.ui.customization

import hivens.core.io.readUtf8
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** Reads and writes customization.json. [publish] is how a file is written whole, supplied by the app root. */
class CustomizationManager(
    configPath: Path,
    private val json: Json,
    private val publish: (file: Path, content: String) -> Unit,
) {
    private val log = LoggerFactory.getLogger(CustomizationManager::class.java)
    private val settingsFile = configPath.resolve("customization.json")

    fun load(): CustomizationSettings {
        if (!Files.exists(settingsFile)) return CustomizationSettings()
        return try {
            json.decodeFromString<CustomizationSettings>(readUtf8(settingsFile))
        } catch (e: Exception) {
            log.error("Failed to load customization settings", e)
            CustomizationSettings()
        }
    }

    fun save(settings: CustomizationSettings) {
        try {
            publish(settingsFile, json.encodeToString(settings))
        } catch (e: Exception) {
            log.error("Failed to save customization settings", e)
        }
    }
}
