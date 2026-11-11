package hivens.launcher.launch

import kotlinx.coroutines.flow.StateFlow

/**
 * The one thing a surface needs to know about a live launch: whose files are in
 * use right now.
 *
 * Narrow on purpose. A screen that only warns before rewriting a running pack has
 * no business being handed the launch orchestrator, and anything rendering that
 * screen -- a test, a second frontend -- should not have to build one to satisfy
 * the dependency. Implemented by [LauncherController], which owns the answer.
 */
interface RunningPackSource {

    /**
     * The pack instance whose game is live, or null when nothing is running or the
     * live session came from the SC server list (which lays its files out under
     * `clients/`, so no pack instance is at stake).
     */
    val runningPackInstanceId: StateFlow<String?>
}
