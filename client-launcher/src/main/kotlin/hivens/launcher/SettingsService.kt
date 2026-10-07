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
 *
 * The file has writers outside this object: the recovery surface switches modules
 * off and resets settings through the raw file while this process may still hold
 * its copy, and the command-line build is a second process. [updateSettings] reads
 * the file again when it no longer holds what this object last read or wrote, so
 * a change made elsewhere is built on rather than written over. A module switched
 * off to get out of a crash loop came back on with the next track change.
 */
class SettingsService(
    private val json: Json,
    private val settingsFile: Path,
) : ISettingsService {

    private val log = LoggerFactory.getLogger(SettingsService::class.java)
    private val lock = Any()
    @Volatile
    private var cachedSettings: SettingsData? = null

    /** The file's text when this object last read or wrote it, null when it was absent. */
    private var lastSeenText: String? = null

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
                val text = json.encodeToString(settings)
                AtomicFiles.writeString(settingsFile, text)
                lastSeenText = text
            } catch (e: IOException) {
                log.error("Failed to save settings", e)
            }
        }
    }

    override fun updateSettings(transform: (SettingsData) -> SettingsData): SettingsData =
        synchronized(lock) {
            if (changedOnDisk()) {
                log.info("settings.json was changed by another writer; reading it again before this change")
                reload()
            }
            val next = transform(getSettings())
            saveSettings(next)
            next
        }

    /** Caller must hold [lock]. */
    private fun changedOnDisk(): Boolean = readTextOrNull() != lastSeenText

    private fun readTextOrNull(): String? = runCatching { Files.readString(settingsFile) }.getOrNull()

    /** Caller must hold [lock]. */
    private fun reload() {
        if (!Files.exists(settingsFile)) {
            cachedSettings = SettingsData()
            lastSeenText = null
            return
        }
        try {
            val text = Files.readString(settingsFile)
            lastSeenText = text
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
