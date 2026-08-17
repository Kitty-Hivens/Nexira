package hivens.launcher

import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import hivens.core.data.foldLegacyExperimentalGate
import hivens.core.io.AtomicFiles
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes settings from both Compose UI threads (settings
 * screen recomposition) and IO coroutines (startup load, override
 * restore). Without coordination two concurrent saves could race the
 * file write and the UI could observe a half-applied SettingsData; all
 * cache access goes through the same monitor lock.
 */
class SettingsService(
    private val json: Json,
    private val settingsFile: Path,
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
                // Atomic: a torn write here is not a corrupt setting, it is every
                // setting. `reload` cannot tell truncated JSON from absent JSON, so
                // it falls back to defaults and the loss never reaches the UI.
                AtomicFiles.writeString(settingsFile, json.encodeToString(settings))
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
            // Fold on the way in, so every reader downstream sees knobs that already
            // account for the retired experimental master. The fold clears the legacy
            // flag, and the next save persists that.
            cachedSettings = foldLegacyExperimentalGate(json.decodeFromString<SettingsData>(text))
        } catch (e: Exception) {
            log.error("Failed to load settings, using defaults", e)
            cachedSettings = SettingsData()
        }
    }
}
