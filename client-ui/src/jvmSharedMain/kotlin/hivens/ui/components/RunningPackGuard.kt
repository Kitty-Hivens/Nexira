package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import hivens.launcher.launch.RunningPackSource
import hivens.ui.i18n.LocalStrings
import org.koin.compose.koinInject

/**
 * Guards the operations that rewrite an installed pack -- update, version switch,
 * rollback, repair -- against being run on the pack whose game is live.
 *
 * It warns and gets out of the way rather than disabling the button. Rewriting
 * `mods/` under a running loader is a bad idea, not an impossible one, and the
 * person doing it may well have a reason the launcher cannot see. What they are
 * owed is knowing what it costs before they spend it, not having the choice taken
 * away.
 *
 * Keyed on the instance, not on "a game is running": only one launch is in flight
 * at a time, and rewriting pack A while pack B plays touches nothing B has open.
 * Warning there would be noise, and noise is how a warning stops being read.
 */
@Composable
internal fun rememberRunningPackGuard(packId: String): RunningPackGuard {
    val running: RunningPackSource = koinInject()
    val runningId by running.runningPackInstanceId.collectAsState()
    val guard = remember(packId) { RunningPackGuard() }
    guard.isRunning = runningId == packId
    return guard
}

/**
 * Host state for [rememberRunningPackGuard]. Call [run] with the action: it either
 * runs it straight away or parks it behind the warning. Render [Dialog] somewhere
 * in the same composable.
 */
internal class RunningPackGuard {
    internal var isRunning: Boolean = false
    private var pending by mutableStateOf<(() -> Unit)?>(null)

    /** Runs [action] now, or asks first when the pack's game is live. */
    fun run(action: () -> Unit) {
        if (isRunning) pending = action else action()
    }

    @Composable
    fun Dialog() {
        val action = pending ?: return
        val s = LocalStrings.current
        DestructiveConfirmDialog(
            title = s.packBusyRunningTitle,
            body = s.packBusyRunningBody,
            confirmLabel = s.packBusyRunningConfirm,
            onConfirm = action,
            onDismiss = { pending = null },
        )
    }
}
