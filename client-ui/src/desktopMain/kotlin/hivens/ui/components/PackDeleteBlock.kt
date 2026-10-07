package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import hivens.core.launch.InstanceWorkRegistry
import hivens.launcher.launch.RunningPackSource
import hivens.ui.i18n.LocalStrings
import org.koin.compose.koinInject

/**
 * Why the pack [packId] cannot be deleted right now, in the words a delete control
 * shows beside itself, or null when it can.
 *
 * The same two questions [hivens.launcher.instance.PackInstanceService.deleteCompletely]
 * asks, asked ahead so the control can say so instead of offering a delete that
 * would be refused. Unlike the rewrites [rememberRunningPackGuard] warns about, a
 * delete under a live game cannot be made to work: the game keeps writing into
 * what is being removed.
 */
@Composable
internal fun rememberPackDeleteBlock(packId: String): String? {
    val s = LocalStrings.current
    val running: RunningPackSource = koinInject()
    val registry: InstanceWorkRegistry = koinInject()
    val runningId by running.runningPackInstanceId.collectAsState()
    val works by registry.current.collectAsState()
    return when {
        runningId == packId -> s.packDeleteBlockedRunning
        else -> works[packId]?.label(s)
    }
}
