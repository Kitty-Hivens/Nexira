package hivens.ui.widgets.about

import hivens.core.data.LauncherUpdate
import hivens.core.jvm.SystemHardware
import hivens.core.jvm.SystemMemory
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

// Tri-state machine the update widget reads and the surface drives.
// Public so the widget files across this package can branch on it;
// nothing outside about touches this type.
sealed class UpdateCheckState {
    object Idle     : UpdateCheckState()
    object Checking : UpdateCheckState()
    object UpToDate : UpdateCheckState()
    data class Available(val update: LauncherUpdate) : UpdateCheckState()
    data class Error(val message: String)            : UpdateCheckState()
}

/**
 * Where the About page's own update check stands. The page runs the check on a
 * timer of its own and puts the answer over its level, so only its widgets see it.
 */
internal val AboutUpdateSource: SourceKey<UpdateCheckState> = SourceKey("about.update", ScopeLevel.Page)

/** Opens the About page's update dialog, which the page draws so it outlives the panel that asked. */
internal val OpenUpdateDialog: CommandKey<Unit> = CommandKey("about.update.open", ScopeLevel.Page)

/**
 * The machine's memory and processor. Read once a run, on the first read, because
 * asking the OS is slow and the answer does not change while the launcher runs.
 * Zero memory means the runtime could not say, which the card shows as unknown
 * rather than as a figure it made up.
 */
class SystemFacts(
    val ramMb: Int,
    val swapMb: Int?,
    val cpu: SystemHardware.CpuInfo,
) {
    companion object {
        fun read(): SystemFacts = SystemFacts(
            ramMb  = SystemMemory.totalPhysicalMbOrNull() ?: 0,
            swapMb = SystemHardware.swapTotalMb,
            cpu    = SystemHardware.cpu,
        )
    }
}
