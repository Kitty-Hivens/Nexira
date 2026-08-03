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
     * is where a jar gets added by hand. Answers off disk, so an offline launch is
     * held to the same rule.
     *
     * [expected] is the pack's own baseline as `mods/` filename -> sha1, taken from
     * the instance's installed manifest in the registry. It is what makes the check
     * mean anything: the roster file beside the mods answers by NAME and lives in a
     * directory its subject can write, so overwriting a named jar, or adding a line
     * to the roster, both pass it. A digest cannot be talked into agreeing.
     *
     * Null falls back to that roster file, for instances installed before a baseline
     * was recorded. That is the weaker answer and it is deliberate: the alternative
     * is refusing a token to every instance predating the field, which is a support
     * problem rather than a defence.
     *
     * A file whose name is expected and whose content is not is NOT deleted -- the
     * verdict simply fails. Removing it mid-launch would leave the pack incomplete
     * and the game broken in a way that reads as the launcher eating an install; the
     * repair path exists and says what to do.
     */
    suspend fun enforceRoster(clientDir: Path, expected: Map<String, String>? = null): RosterVerdict
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
    /** Named by the pack, present on disk, and not the bytes the pack named. */
    val mismatched: List<String> = emptyList(),
)
