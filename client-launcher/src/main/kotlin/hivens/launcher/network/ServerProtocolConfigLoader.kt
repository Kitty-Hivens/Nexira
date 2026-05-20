package hivens.launcher.network

import hivens.config.ExperimentalConduitOverride
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads [ServerProtocolConfig] from `<dataDir>/server-config.json` if
 * present, falling back to defaults if absent. Applies the system-property
 * override on top via [ServerProtocolConfig.resolve].
 *
 * Failure modes (malformed JSON, partial fields):
 * - Malformed JSON -> log warn, use defaults. Nexira should not fail to start
 *   because someone hand-edited the file wrong.
 * - Unknown extra fields -> tolerated by [Json.ignoreUnknownKeys].
 * - Missing fields -> defaults from [ServerProtocolConfig] data class.
 */
class ServerProtocolConfigLoader(
    private val json: Json,
) {
    private val logger = LoggerFactory.getLogger(ServerProtocolConfigLoader::class.java)

    /**
     * Read and resolve the effective config for this launcher session.
     * Called once at DI setup; the resulting [ServerProtocolConfig] is
     * a singleton consumed by [SmartycraftV1Protocol], [LauncherHashCache],
     * and [FileDownloadService].
     */
    @OptIn(ExperimentalConduitOverride::class)
    fun load(dataDir: Path): ServerProtocolConfig {
        val file = dataDir.resolve(CONFIG_FILE_NAME)
        val loaded = if (Files.exists(file)) {
            try {
                val text = Files.readString(file)
                json.decodeFromString(ServerProtocolConfig.serializer(), text)
            } catch (e: SerializationException) {
                logger.warn("Could not parse {}: {} -- using defaults", file, e.message)
                ServerProtocolConfig()
            } catch (e: Exception) {
                logger.warn("Could not read {}: {} -- using defaults", file, e.message)
                ServerProtocolConfig()
            }
        } else {
            ServerProtocolConfig()
        }
        val resolved = ServerProtocolConfig.resolve(loaded)
        if (resolved.baseUrl != ServerProtocolConfig.DEFAULT_BASE_URL) {
            logger.info(
                "Using non-default SmartyCraft baseUrl: {} (default would be {})",
                resolved.baseUrl,
                ServerProtocolConfig.DEFAULT_BASE_URL,
            )
        }
        return resolved
    }

    companion object {
        const val CONFIG_FILE_NAME = "server-config.json"
    }
}
