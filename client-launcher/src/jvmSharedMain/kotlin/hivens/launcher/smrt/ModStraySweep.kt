package hivens.launcher.smrt

import java.nio.file.Path

/**
 * The strict reading of `mods/` a reattach holds a pack to: which files there a build does
 * not account for, and taking exactly those away. One rule for every source, so a reattach
 * means the same whichever source the pack came from.
 */
interface ModStraySweep {
    /**
     * What in [clientDir]'s `mods/` the build does not place. [expected] names each file the
     * build places at the top of `mods/`, with its sha1 where known, and [ownPaths] every path
     * under `mods/` it places at all, versioned folders included. Reads and touches nothing.
     */
    fun strays(clientDir: Path, expected: Map<String, String>, ownPaths: Set<String>): StrayScan

    /** Removes [paths] from [clientDir], and says what went and what would not. */
    fun dropStrays(clientDir: Path, paths: Collection<String>): StrayDrop
}

/** The sweep the sync engine runs, see [SmrtSyncService.strays] and [SmrtSyncService.dropStrays]. */
class SyncStraySweep(private val sync: SmrtSyncService) : ModStraySweep {
    override fun strays(clientDir: Path, expected: Map<String, String>, ownPaths: Set<String>): StrayScan =
        sync.strays(clientDir, expected, ownPaths)

    override fun dropStrays(clientDir: Path, paths: Collection<String>): StrayDrop = sync.dropStrays(clientDir, paths)
}
