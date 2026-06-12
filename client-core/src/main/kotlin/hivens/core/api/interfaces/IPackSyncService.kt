package hivens.core.api.interfaces

import hivens.core.api.dto.smrt.SmrtModEntry
import java.nio.file.Path

/**
 * Relabels already-downloaded pack mods on disk to match an optional-content
 * toggle state (a flip is a `.disabled` rename, no network). The slice of the
 * launcher's sync service the controller's toggle path uses.
 */
interface IPackSyncService {
    fun relabel(clientDir: Path, mods: List<SmrtModEntry>, enabledState: Map<String, Boolean>)
}
