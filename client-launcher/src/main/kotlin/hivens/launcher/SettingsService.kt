package hivens.launcher

import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Read+write to the settings cache happens from both Compose UI threads
 * (settings screen recomposition) and IO coroutines (startup load,
 * Conduit Phase 2 force-proxy restore). Without coordination two
 * concurrent saves could race the file write, and the UI could observe a
 * half-applied SettingsData. All cache access goes through the same
 * monitor lock (#189).
 */
class SettingsService(
    private val json: Json,
    private val settingsFile: Path
) : ISettingsService {

    private val log = LoggerFactory.getLogger(SettingsService::class.java)
    private val lock = Any()
    @Volatile
    private var cachedSettings: SettingsData? = null

    init {
        synchronized(lock) { reload() }
    }

    override fun getSettings(): SettingsData = synchronized(lock) {
        if (cachedSettings == null) reload()
        cachedSettings ?: SettingsData()
    }

    override fun saveSettings(settings: SettingsData) {
        synchronized(lock) {
            cachedSettings = settings
            try {
                if (settingsFile.parent != null) Files.createDirectories(settingsFile.parent)
                val text = json.encodeToString(settings)
                Files.writeString(settingsFile, text)
            } catch (e: IOException) {
                log.error("Failed to save settings", e)
            }
        }
    }

    /** Caller must hold [lock]. */
    private fun reload() {
        if (!Files.exists(settingsFile)) {
            cachedSettings = SettingsData()
            return
        }
        try {
            val text = Files.readString(settingsFile)
            cachedSettings = json.decodeFromString<SettingsData>(text)
        } catch (e: Exception) {
            log.error("Failed to load settings, using defaults", e)
            cachedSettings = SettingsData()
        }
    }
}
