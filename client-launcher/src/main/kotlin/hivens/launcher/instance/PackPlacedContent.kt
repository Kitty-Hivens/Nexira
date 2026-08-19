package hivens.launcher.instance

import hivens.launcher.update.PackFileRecord
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which files in an instance the pack put there, for callers that need to tell
 * the pack's content from the player's own.
 *
 * A narrow read over the record the installer writes. The record exists so an
 * update knows what it may retire; the same list answers who owns a row on
 * screen, and reading it here keeps that question from being guessed at from the
 * pack's origin -- which is how content installed from a source other than the
 * mirror ended up locked in full, the player's own files with it.
 */
object PackPlacedContent {

    /**
     * Paths the pack placed, relative to [instanceDir], or null when the instance
     * carries no record at all.
     *
     * Null and empty are different answers and the caller must keep them apart:
     * empty means the pack placed nothing, while null means nothing here knows
     * what the pack placed -- an instance from a source that writes no record, or
     * one installed before it did. Treating the second as the first would hand
     * the player edit rights over files that an update still believes it owns.
     */
    fun paths(instanceDir: Path): Set<String>? {
        if (!Files.isRegularFile(instanceDir.resolve(PackFileRecord.FILE_NAME))) return null
        return PackFileRecord.read(instanceDir).keys
    }
}
