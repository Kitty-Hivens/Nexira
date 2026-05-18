package hivens.launcher.launch

import hivens.core.data.LauncherLogType

/**
 * One-shot launch-flow events emitted by [LauncherController] for the
 * UI's console pane to consume. Replaces the controller's prior direct
 * calls into `GameConsoleService.append(...)` -- backend produces
 * semantic events, UI translates them to localized text and routes
 * them into the console widget.
 *
 * High-volume [ProcessOutput] events (game stdout / stderr) flow through
 * the same channel; the controller's `SharedFlow` is configured with a
 * bounded buffer and `DROP_OLDEST` so a Forge / NeoForge startup storm
 * cannot back-pressure the launch coroutine. Loss-under-pressure is
 * acceptable -- the in-memory console buffer in `GameConsoleService`
 * is itself capped at 2000 lines.
 *
 * Reuses [LauncherLogType] (already in `client-core`) for severity so
 * no new enum is introduced.
 */
sealed class LaunchLogEvent {
    /** Opens a new per-session game-output log file and adds the divider. */
    data object SessionStarted : LaunchLogEvent()

    /** First info line for a new session: `"<AppName>..."`. */
    data object AppBanner : LaunchLogEvent()

    /** Target server announcement: `"-> <name>"`, optionally with offline tag. */
    data class TargetServer(val name: String, val offline: Boolean) : LaunchLogEvent()

    data object OfflineSkipAuth : LaunchLogEvent()

    data class AuthSucceeded(val uuid: String) : LaunchLogEvent()

    /** Cached credentials missing or blank; auth attempted with offline fallback. */
    data object NoPassword : LaunchLogEvent()

    /** Non-2FA auth failure; flow continues with stale session if any. */
    data class AuthFailed(val message: String?) : LaunchLogEvent()

    data object OfflineSkipSync : LaunchLogEvent()

    data object Launching : LaunchLogEvent()

    /** A single line from the spawned game process's stdout or stderr. */
    data class ProcessOutput(val text: String, val severity: LauncherLogType) : LaunchLogEvent()

    /**
     * Terminal-state error has just been announced via [LaunchState.Error].
     * Emitted alongside the state change so the UI's console pane can show
     * the localized reason; backend stays free of i18n. Pairs with
     * [RequestConsoleVisible] in every failure path.
     */
    data class Error(val reason: LaunchError) : LaunchLogEvent()

    /** Controller asks the UI to make the console window visible (post-error / launch). */
    data object RequestConsoleVisible : LaunchLogEvent()
}
