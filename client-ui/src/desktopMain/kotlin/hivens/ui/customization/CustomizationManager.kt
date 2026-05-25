package hivens.ui.customization

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

class CustomizationManager(
    configPath: Path,
    private val json: Json,
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
            Files.createDirectories(settingsFile.parent)
            Files.writeString(settingsFile, json.encodeToString(settings))
        } catch (e: Exception) {
            log.error("Failed to save customization settings", e)
        }
    }
}
