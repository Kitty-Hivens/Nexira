package hivens.ui.background

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Manages loading/saving of [BackgroundSettings] to disk.
 */
class BackgroundManager(
    configPath: Path,
    private val json: Json
) {
    private val logger = LoggerFactory.getLogger(BackgroundManager::class.java)
    private val settingsFile = configPath.resolve("background.json")

    fun load(): BackgroundSettings {
        if (!Files.exists(settingsFile)) return BackgroundSettings()
        return try {
            json.decodeFromString<BackgroundSettings>(Files.readString(settingsFile))
        } catch (e: Exception) {
            logger.error("Failed to load background settings", e)
            BackgroundSettings()
        }
    }

    fun save(settings: BackgroundSettings) {
        try {
            Files.createDirectories(settingsFile.parent)
            Files.writeString(settingsFile, json.encodeToString(settings))
        } catch (e: Exception) {
            logger.error("Failed to save background settings", e)
        }
    }
}
