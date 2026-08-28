package hivens.launcher

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * User-config files inside a `clients/` install that the launcher must NEVER
 * overwrite even when the server's manifest claims they're stale. Without this
 * gate, every server-side modpack push would erase a player's hand-tuned
 * settings.
 *
 * Scope is the SmartyCraft server-list path and nothing else. That path
 * downloads whatever the server lists and keeps no record of what it wrote last
 * time, so a list of names to leave alone is the only thing standing between an
 * update and the player's settings -- a workaround for how that path behaves,
 * not a design. It retires when that path does.
 *
 * Mirror packs do not use this and must not: they keep the installed version's
 * manifest as a baseline, so the reconciler distinguishes a file the pack
 * shipped from one the player wrote and decides each on its own evidence. A
 * name-based list cannot make that distinction, and carrying it there was
 * actively harmful -- a listed name was skipped in both directions, so a pack
 * could not deliver its own `servers.dat` at all.
 *
 * Defaults cover in-game settings (`options.txt`, `servers.dat`) and
 * per-mod state directories that mod authors traditionally store
 * directly under the client root (Xaero's minimap waypoints, VoxelMap
 * configs, JourneyMap data, JEI bookmarks).
 *
 * Externalized: users extend the list without recompiling -- drop a
 * mod name into `protected-paths.json` in the data dir and restart.
 * On first run the file is created with the defaults so users see
 * what's already covered before adding their own.
 *
 * `endsWith` matches exact filenames (case-insensitive, after path
 * normalization); `contains` names a mod's state DIRECTORY and is matched
 * against the directory portion of the path only, never the filename. A mod
 * jar lives at `mods/<name>-<version>.jar`, so matching the whole path made
 * every entry here protect its own mod's jar as well as its config -- and a
 * protected jar is skipped before the hash compare, before the corrupt-archive
 * check, and by both update reconcilers, so it never updates, never retires on
 * a version bump, and never repairs.
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
        if (config.endsWith.any { lower.endsWith(it.lowercase()) }) return true
        val dir = lower.substringBeforeLast('/', missingDelimiterValue = "")
        if (dir.isEmpty()) return false
        return config.contains.any { dir.contains(it.lowercase()) }
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
