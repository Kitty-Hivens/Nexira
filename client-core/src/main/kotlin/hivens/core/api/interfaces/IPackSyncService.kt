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
}
