package hivens.launcher

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * User-config files inside a synced client install that the launcher must
 * NEVER overwrite even when the server's manifest claims they're stale.
 *
 * The default list covers in-game settings (`options.txt`, `servers.dat`)
 * and per-mod state directories that mod authors traditionally store
 * directly under the client root (Xaero's minimap waypoints, VoxelMap
 * configs, JourneyMap data, JEI bookmarks). Without this gate, every
 * server-side modpack push would erase a player's hand-tuned settings.
 *
 * Externalized so users can extend the list without recompiling -- drop a
 * mod name into `~/.local/share/aura-launcher/protected-paths.json` and
 * restart. On first run the file is created with the defaults so users
 * can see what's already covered before adding their own.
 *
 * `endsWith` matches exact filenames (case-insensitive, after path
 * normalization), `contains` matches anywhere in the relative path
 * (typically a mod directory name).
 */
@Serializable
data class ProtectedPathsConfig(
    val endsWith: List<String> = listOf(
        "options.txt",
        "servers.dat",
    ),
    val contains: List<String> = listOf(
        "xaerominimap",
        "xaeroworldmap",
        "voxelmap",
        "journeymap",
        "jei",
    ),
)

class ProtectedPaths(
    private val configFile: Path,
    private val json: Json,
) {
    private val log = LoggerFactory.getLogger(ProtectedPaths::class.java)

    private val config: ProtectedPathsConfig by lazy { loadOrCreate() }

    fun isProtected(relativePath: String): Boolean {
        val lower = relativePath.lowercase().replace("\\", "/")
        return config.endsWith.any { lower.endsWith(it.lowercase()) } ||
                config.contains.any { lower.contains(it.lowercase()) }
    }

    private fun loadOrCreate(): ProtectedPathsConfig {
        if (!Files.exists(configFile)) {
            val defaults = ProtectedPathsConfig()
            runCatching {
                configFile.parent?.let { Files.createDirectories(it) }
                Files.writeString(configFile, json.encodeToString(defaults))
                log.info("Wrote default protected-paths.json at {}", configFile)
            }.onFailure {
                log.warn("Failed to write default protected-paths.json -- using in-memory defaults", it)
            }
            return defaults
        }
        return runCatching {
            val text = Files.readString(configFile)
            json.decodeFromString<ProtectedPathsConfig>(text)
        }.onFailure {
            log.warn("Failed to parse protected-paths.json -- falling back to defaults (in-memory only, file left intact)", it)
        }.getOrDefault(ProtectedPathsConfig())
    }
}
