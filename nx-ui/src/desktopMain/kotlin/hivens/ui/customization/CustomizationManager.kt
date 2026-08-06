package hivens.ui.customization

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * [publish] is supplied by the app root for the reason spelled out on
 * [hivens.ui.theme.ThemeManager]: this module has no project dependencies, and a
 * second copy of the atomic-write sequence would be a durability primitive
 * maintained in two places.
 */
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
            json.decodeFromString<CustomizationSettings>(Files.readString(settingsFile))
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
