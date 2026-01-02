package hivens.launcher

import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class SettingsService(
    private val json: Json,
    private val settingsFile: Path
) : ISettingsService {

    private val log = LoggerFactory.getLogger(SettingsService::class.java)
    private var cachedSettings: SettingsData? = null

    init {
        reload()
    }

    override fun getSettings(): SettingsData {
        if (cachedSettings == null) reload()
        return cachedSettings ?: SettingsData.defaults()
    }

    override fun saveSettings(settings: SettingsData) {
        this.cachedSettings = settings
        try {
            if (settingsFile.parent != null) Files.createDirectories(settingsFile.parent)
            val text = json.encodeToString(settings)
            Files.writeString(settingsFile, text)
        } catch (e: IOException) {
            log.error("Failed to save settings", e)
        }
    }

    private fun reload() {
        if (!Files.exists(settingsFile)) {
            cachedSettings = SettingsData.defaults()
            return
        }
        try {
            val text = Files.readString(settingsFile)
            cachedSettings = json.decodeFromString<SettingsData>(text)
        } catch (e: Exception) {
            log.error("Failed to load settings, using defaults", e)
            cachedSettings = SettingsData.defaults()
        }
    }
}
