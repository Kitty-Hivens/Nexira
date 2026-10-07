package hivens.ui.background

import hivens.core.io.AtomicFiles
import hivens.ui.bootstrap.RecoveryIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The wallpaper settings: the one copy everything reads, and its file.
 *
 * One copy because there are several writers. The settings screen, the player's
 * volume and a transcode finishing after the screen that started it has gone all
 * change it, and a writer holding a copy of its own put back whatever the others
 * had changed in the meantime.
 *
 * A change is live at once and written behind a short debounce, by [flush]. What is
 * still unwritten at exit is flushed by a JVM shutdown hook, so a quit from the tray
 * or a crash-restart inside the debounce keeps the last adjustment. One per process:
 * a copy per shell would add a hook per restart.
 */
class BackgroundManager(
    configPath: Path,
    private val json: Json,
) {
    private val logger = LoggerFactory.getLogger(BackgroundManager::class.java)
    private val settingsFile = configPath.resolve("background.json")
    private val lock = Any()

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<BackgroundSettings> = _settings.asStateFlow()

    @Volatile
    private var written: BackgroundSettings = _settings.value

    // Built here rather than at shutdown, for the reason WidgetStateFlushHook gives:
    // a class first loaded while the process exits may no longer be readable.
    private val onShutdown = Runnable { runCatching { flush() } }

    init {
        Runtime.getRuntime().addShutdownHook(Thread(onShutdown, "nexira-background-flush"))
    }

    /** Applies [transform] to the settings as they stand now, not to a copy read earlier. */
    fun update(transform: (BackgroundSettings) -> BackgroundSettings) {
        _settings.update(transform)
    }

    /** Writes the settings if they changed since the last write. */
    fun flush() {
        synchronized(lock) {
            val current = _settings.value
            if (current == written) return
            // The recovery surface deleted the file to put the wallpaper back to its
            // defaults, and writing the copy in memory would undo that at the next start.
            if (RecoveryIo.stateWasReset) {
                logger.debug("Background settings were reset from the recovery surface -- not writing the in-memory copy back")
                return
            }
            try {
                AtomicFiles.writeString(settingsFile, json.encodeToString(current))
                written = current
            } catch (e: Exception) {
                logger.error("Failed to save background settings", e)
            }
        }
    }

    private fun read(): BackgroundSettings {
        if (!Files.exists(settingsFile)) return BackgroundSettings()
        return try {
            json.decodeFromString<BackgroundSettings>(Files.readString(settingsFile))
        } catch (e: Exception) {
            logger.error("Failed to load background settings", e)
            BackgroundSettings()
        }
    }
}
