package hivens.ui.background

import hivens.core.io.AtomicFiles
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * The wallpaper settings on disk, written behind a short debounce.
 *
 * The shell [stage]s every change at once and [flush]es after the debounce. What is
 * staged and not yet written is flushed by a JVM shutdown hook, and is what [load]
 * answers, so a quit from the tray or a crash-restart inside the debounce keeps the
 * last slider position. One per process: a remembered copy per shell would add a hook per restart.
 */
class BackgroundManager(
    configPath: Path,
    private val json: Json,
) {
    private val logger = LoggerFactory.getLogger(BackgroundManager::class.java)
    private val settingsFile = configPath.resolve("background.json")
    private val lock = Any()

    @Volatile
    private var pending: BackgroundSettings? = null

    // Built here rather than at shutdown, for the reason WidgetStateFlushHook gives:
    // a class first loaded while the process exits may no longer be readable.
    private val onShutdown = Runnable { runCatching { flush() } }

    init {
        Runtime.getRuntime().addShutdownHook(Thread(onShutdown, "nexira-background-flush"))
    }

    /** The settings as they stand, the staged ones included. */
    fun load(): BackgroundSettings {
        pending?.let { return it }
        if (!Files.exists(settingsFile)) return BackgroundSettings()
        return try {
            json.decodeFromString<BackgroundSettings>(Files.readString(settingsFile))
        } catch (e: Exception) {
            logger.error("Failed to load background settings", e)
            BackgroundSettings()
        }
    }

    /** Takes [settings] as the current ones, to be written by the next [flush]. */
    fun stage(settings: BackgroundSettings) {
        pending = settings
    }

    /** Writes what is staged, if anything. */
    fun flush() {
        synchronized(lock) {
            val toWrite = pending ?: return
            try {
                AtomicFiles.writeString(settingsFile, json.encodeToString(toWrite))
                if (pending === toWrite) pending = null
            } catch (e: Exception) {
                logger.error("Failed to save background settings", e)
            }
        }
    }

    fun save(settings: BackgroundSettings) {
        stage(settings)
        flush()
    }
}
