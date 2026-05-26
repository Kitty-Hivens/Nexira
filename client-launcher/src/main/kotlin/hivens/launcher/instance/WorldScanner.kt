package hivens.launcher.instance

import hivens.core.data.GameMode
import hivens.core.data.WorldDimension
import hivens.core.data.WorldEntry
import hivens.launcher.nbt.Nbt
import hivens.launcher.nbt.NbtException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Scans `<instanceDir>/saves/` for Minecraft worlds. Each world dir
 * is decoded into a [WorldEntry]; corrupt or unreadable worlds are
 * logged and skipped so one bad save never blocks the whole list.
 *
 * Cheap enough to call on every Library PackDetail open: a typical
 * player keeps < 30 worlds, each is a single GZIP decompress + a
 * shallow NBT walk.
 */
class WorldScanner {

    private val log = LoggerFactory.getLogger(WorldScanner::class.java)

    suspend fun scan(instanceDir: Path): List<WorldEntry> = withContext(Dispatchers.IO) {
        val savesDir = instanceDir.resolve("saves")
        if (!savesDir.exists() || !savesDir.isDirectory()) return@withContext emptyList()

        Files.list(savesDir).use { stream ->
            stream
                .filter { it.isDirectory() }
                .map { worldDir ->
                    try {
                        readWorld(worldDir)
                    } catch (e: Exception) {
                        log.warn("Skipping unreadable world at {}", worldDir, e)
                        null
                    }
                }
                .filter { it != null }
                .map { it!! }
                .sorted(compareByDescending<WorldEntry> { it.lastPlayedEpochMs }.thenBy { it.displayName })
                .toList()
        }
    }

    private fun readWorld(worldDir: Path): WorldEntry? {
        val levelDat = worldDir.resolve("level.dat")
        if (!levelDat.isRegularFile()) return null

        val root = try {
            Files.newInputStream(levelDat).use { Nbt.read(it, gzipped = true) }
        } catch (e: NbtException) {
            log.warn("level.dat at {} did not parse: {}", levelDat, e.message)
            return null
        }
        val data = root.value.compound("Data") ?: return null

        val displayName = data.string("LevelName")?.takeIf { it.isNotBlank() } ?: worldDir.name
        val lastPlayed  = data.long("LastPlayed") ?: 0L
        // Seed lookup -- 1.16+ moved it under WorldGenSettings.seed (long),
        // pre-1.16 stored it at Data.RandomSeed (long).
        val seed = data.long("RandomSeed")
            ?: data.compound("WorldGenSettings")?.long("seed")
        val gameMode = data.int("GameType")?.let { gameModeOf(it) }
        val mcVersion = data.compound("Version")?.string("Name")
        val dimensions = detectDimensions(worldDir)
        val iconPath = worldDir.resolve("icon.png").takeIf { it.isRegularFile() }?.toAbsolutePath()?.toString()

        return WorldEntry(
            dirName = worldDir.name,
            displayName = displayName,
            lastPlayedEpochMs = lastPlayed,
            seed = seed,
            gameMode = gameMode,
            mcVersion = mcVersion,
            dimensions = dimensions,
            iconPath = iconPath,
        )
    }

    private fun detectDimensions(worldDir: Path): List<WorldDimension> {
        val out = mutableListOf<WorldDimension>()
        // Overworld is implicit when region/ exists.
        if (worldDir.resolve("region").isDirectory()) out += WorldDimension.Overworld
        if (worldDir.resolve("DIM-1").isDirectory())   out += WorldDimension.Nether
        if (worldDir.resolve("DIM1").isDirectory())    out += WorldDimension.End
        // Modded / datapack dimensions land under dimensions/<namespace>/<id>/.
        val dimensionsRoot = worldDir.resolve("dimensions")
        if (dimensionsRoot.isDirectory() && Files.list(dimensionsRoot).use { it.findAny().isPresent }) {
            out += WorldDimension.Other
        }
        return out
    }

    private fun gameModeOf(value: Int): GameMode = when (value) {
        0 -> GameMode.Survival
        1 -> GameMode.Creative
        2 -> GameMode.Adventure
        3 -> GameMode.Spectator
        else -> GameMode.Unknown
    }
}
