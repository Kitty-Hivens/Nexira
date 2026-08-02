package hivens.core.api.interfaces

import hivens.core.api.dto.smrt.SmrtModEntry
import java.nio.file.Path

/**
 * Relabels already-downloaded pack mods on disk to match an optional-content
 * toggle state (a flip is a `.disabled` rename, no network). The slice of the
 * launcher's sync service the controller's toggle path uses.
 */
interface IPackSyncService {
    /**
     * @return the filenames whose flip could not be applied on disk right now (the
     * file is held open by a live process -- on Windows a running game keeps its mod
     * jars locked). The toggle is still persisted, so the next sync applies it; an
     * empty list means every flip landed.
     */
    fun relabel(clientDir: Path, mods: List<SmrtModEntry>, enabledState: Map<String, Boolean>): List<String>

    /**
     * Holds an installed instance to the pack it claims to be: deletes everything
     * under `mods/` the pack does not name, and reports whether the check could be
     * made at all.
     *
     * Called before every spawn, not just on sync, because the gap between two syncs
     * is where a jar gets added by hand. Answers from the roster written to the
     * instance at sync time, so an offline launch is held to the same rule.
     */
    suspend fun enforceRoster(clientDir: Path): RosterVerdict
}

/**
 * Outcome of holding an instance to its pack.
 *
 * [verified] false means the instance was NOT brought in line with the pack, for
 * either of two reasons, and both deny the launch a session token.
 *
 * The first is that there was no roster to check against -- an instance from before
 * the file existed, or one whose roster could not be read. Nothing is deleted in that
 * case (an empty roster is indistinguishable from "the pack has no mods", and acting
 * on the guess would empty a working instance).
 *
 * The second is [blocked]: files the roster does not name that could not be removed --
 * read-only, denied by permissions, or held open. That is not a technicality. Making
 * the file undeletable is precisely how one keeps something in `mods/` across a launch,
 * so a failed delete has to read as "this instance is not what the pack says it is",
 * never as "nothing to remove".
 */
data class RosterVerdict(
    val verified: Boolean,
    val removed: List<String> = emptyList(),
    val blocked: List<String> = emptyList(),
)
