package hivens.launcher

import com.google.gson.Gson
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class SettingsService(
    private val gson: Gson,
    private val settingsFile: Path
) : ISettingsService {

    private val log = LoggerFactory.getLogger(SettingsService::class.java)
    private var cachedSettings: SettingsData? = null

    init {
        reload()
    }

    override fun getSettings(): SettingsData {
        if (cachedSettings == null) {
            reload()
        }
        return cachedSettings ?: SettingsData.defaults()
    }

    override fun saveSettings(settings: SettingsData) {
        this.cachedSettings = settings
        try {
            if (settingsFile.parent != null) {
                Files.createDirectories(settingsFile.parent)
            }
            Files.newBufferedWriter(settingsFile).use { writer ->
                gson.toJson(settings, writer)
            }
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
            Files.newBufferedReader(settingsFile).use { reader ->
                cachedSettings = gson.fromJson(reader, SettingsData::class.java)
                if (cachedSettings == null) cachedSettings = SettingsData.defaults()
            }
        } catch (e: IOException) {
            log.error("Failed to load settings, using defaults", e)
            cachedSettings = SettingsData.defaults()
        }
    }
}