package hivens.launcher.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads [MsaConfig] from `<dataDir>/msa-config.json` if present, else defaults
 * (blank client id = Microsoft sign-in disabled). Applies the sysprop/env
 * client-id override via [MsaConfig.resolve].
 *
 * Malformed JSON -> log warn, use defaults: the launcher must not fail to start
 * because someone hand-edited the file wrong. Unknown extra fields are tolerated
 * by the lenient [Json].
 */
class MsaConfigLoader(
    private val json: Json,
) {
    private val logger = LoggerFactory.getLogger(MsaConfigLoader::class.java)

    fun load(dataDir: Path): MsaConfig {
        val file = dataDir.resolve(CONFIG_FILE_NAME)
        val loaded = if (Files.exists(file)) {
            try {
                json.decodeFromString(MsaConfig.serializer(), Files.readString(file))
            } catch (e: SerializationException) {
                logger.warn("Could not parse {}: {} -- Microsoft sign-in disabled", file, e.message)
                MsaConfig()
            } catch (e: Exception) {
                logger.warn("Could not read {}: {} -- Microsoft sign-in disabled", file, e.message)
                MsaConfig()
            }
        } else {
            MsaConfig()
        }
        val resolved = MsaConfig.resolve(loaded)
        if (resolved.enabled) {
            logger.info("Microsoft sign-in enabled (client id configured)")
        }
        return resolved
    }

    companion object {
        const val CONFIG_FILE_NAME = "msa-config.json"
    }
}
