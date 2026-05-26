package hivens.core.data

/**
 * One Minecraft world inside a pack instance's `saves/` directory.
 * Built fresh from disk on every Library PackDetail open (no
 * persistence layer -- the world dir itself is the source of truth
 * and rescanning is cheap for the handful of worlds a typical player
 * keeps around).
 */
data class WorldEntry(
    /** Folder name under `<instanceDir>/saves/`. Stable across rescans. */
    val dirName: String,
    /** Human label from level.dat's `Data.LevelName`. Falls back to `dirName` when missing. */
    val displayName: String,
    /** Unix ms timestamp from `Data.LastPlayed`. Zero when level.dat had no value (newly-created worlds). */
    val lastPlayedEpochMs: Long,
    /**
     * World seed. Pre-1.16 lives at `Data.RandomSeed`; 1.16+ at
     * `Data.WorldGenSettings.seed`. Null when both absent.
     */
    val seed: Long?,
    /** Game mode at the last save. Null when `Data.GameType` absent. */
    val gameMode: GameMode?,
    /** MC version string from `Data.Version.Name`. Useful for cross-pack compat warnings. */
    val mcVersion: String?,
    /**
     * Dimensions whose region data is present on disk. Detected by
     * subfolder presence (`region/`, `DIM-1/`, `DIM1/`, `dimensions/`)
     * rather than parsed from level.dat, because the on-disk view is
     * the only one that reflects actual generated content.
     */
    val dimensions: List<WorldDimension>,
    /** Absolute path string to `icon.png` if the world has one (MC writes it on each save); null otherwise. */
    val iconPath: String?,
)

enum class GameMode { Survival, Creative, Adventure, Spectator, Unknown }

enum class WorldDimension(val tag: String) {
    Overworld("overworld"),
    Nether("the_nether"),
    End("the_end"),
    /** Modded / datapack-added dimensions. The display label is the on-disk folder pattern that matched. */
    Other("other"),
}
