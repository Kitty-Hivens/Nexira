package hivens.ui.widgets.about

import hivens.core.data.LauncherUpdate
import hivens.core.jvm.SystemHardware
import hivens.core.jvm.SystemMemory
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

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

/**
 * What the update panel is shown on where no About page runs a check: up to date,
 * which draws the version this build really is and invents no update.
 */
internal fun sampleUpdateCheck(): UpdateCheckState = UpdateCheckState.UpToDate

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
        /** Asks the OS, which reads files under /proc and /sys. Not for the main thread. */
        fun read(): SystemFacts = SystemFacts(
            ramMb  = SystemMemory.totalPhysicalMbOrNull() ?: 0,
            swapMb = SystemHardware.swapTotalMb,
            cpu    = SystemHardware.cpu,
        )

        /** What the card shows until [read] answers: everything unknown but the thread count the runtime knows. */
        fun unknown(): SystemFacts = SystemFacts(
            ramMb  = 0,
            swapMb = null,
            cpu    = SystemHardware.CpuInfo(null, Runtime.getRuntime().availableProcessors(), null, null),
        )
    }
}

/**
 * The facts as a value that starts unknown and is filled in by [read] on [io].
 *
 * The source is made the first time the About card asks, which is in the middle of
 * laying the page out on the main thread, and reading the files there stalled it.
 * A read that fails leaves the facts unknown.
 */
internal fun systemFactsReadOff(
    scope: CoroutineScope,
    io: CoroutineDispatcher = Dispatchers.IO,
    read: () -> SystemFacts = SystemFacts::read,
): StateFlow<SystemFacts> {
    val facts = MutableStateFlow(SystemFacts.unknown())
    scope.launch(io) {
        runCatching(read)
            .onSuccess { facts.value = it }
            .onFailure { LoggerFactory.getLogger("SystemFacts").warn("The machine's memory and processor could not be read", it) }
    }
    return facts
}
