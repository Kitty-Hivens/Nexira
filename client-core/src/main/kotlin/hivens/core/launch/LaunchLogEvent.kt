package hivens.core.launch

import hivens.core.data.LauncherLogType

/**
 * One-shot launch-flow events emitted by the launch orchestrator for the UI's
 * console pane to consume -- backend produces semantic events, UI translates
 * them to localized text and routes them into the console widget.
 *
 * High-volume [ProcessOutput] events (game stdout / stderr) flow through the
 * same channel; the orchestrator's `SharedFlow` is configured with a bounded
 * buffer and `DROP_OLDEST` so a Forge / NeoForge startup storm cannot
 * back-pressure the launch coroutine. Loss-under-pressure is acceptable -- the
 * in-memory console buffer is itself capped.
 *
 * Reuses [LauncherLogType] for severity so no new enum is introduced.
 */
sealed class LaunchLogEvent {
    /**
     * Opens a new per-session game-output log file and adds the divider.
     * Carries the launch target's stable id + display label so the
     * console can name the session file per-pack (game-output-<id>-*.log)
     * and scope the PackDetail Logs tab to its own pack's sessions.
     * Null id falls back to an unscoped session file.
     */
    data class SessionStarted(
        val targetId: String? = null,
        val targetLabel: String? = null,
    ) : LaunchLogEvent()

    /** First info line for a new session: `"<AppName>..."`. */
    data object AppBanner : LaunchLogEvent()

    /** Target server announcement: `"-> <name>"`, optionally with offline tag. */
    data class TargetServer(val name: String, val offline: Boolean) : LaunchLogEvent()

    data object OfflineSkipAuth : LaunchLogEvent()

    data class AuthSucceeded(val uuid: String) : LaunchLogEvent()

    /** Cached credentials missing or blank; auth attempted with offline fallback. */
    data object NoPassword : LaunchLogEvent()

    /**
     * Non-2FA auth failure; flow continues with stale session if any. [cause]
     * separates the cases the UI has to phrase differently -- see
     * [AuthRefreshFailure], which is the whole reason this carries more than
     * the exception's message.
     */
    data class AuthFailed(
        val message: String?,
        val cause: AuthRefreshFailure = AuthRefreshFailure.Unknown,
    ) : LaunchLogEvent()

    /**
     * Files removed from `mods/` before spawn because the pack does not name them.
     * Carries the relative paths so the console can list exactly what went, rather
     * than leaving the user to guess why a mod they added is not loading.
     */
    data class ForeignContentRemoved(val paths: List<String>) : LaunchLogEvent()

    /**
     * The instance carries no roster, so nothing could vouch for what is in it and
     * the launch goes ahead without a session token. Distinct from
     * [OfflineSkipAuth]: the user did not choose this, and the way out (sync the
     * pack) is not something they can guess from an offline notice.
     */
    data object InstanceUnverified : LaunchLogEvent()

    /**
     * A second-factor session was carried into the launch as-is. Refreshing it is
     * what breaks it -- SmartyCraft invalidates the previous uid on every login --
     * so this is the healthy path, not a degradation.
     */
    data object TwoFactorSessionKept : LaunchLogEvent()

    data object OfflineSkipSync : LaunchLogEvent()

    data object Launching : LaunchLogEvent()

    /** A single line from the spawned game process's stdout or stderr. */
    data class ProcessOutput(val text: String, val severity: LauncherLogType) : LaunchLogEvent()

    /**
     * Terminal-state error has just been announced via [LaunchState.Error].
     * Emitted alongside the state change so the UI's console pane can show
     * the localized reason; backend stays free of i18n. The console is no
     * longer auto-shown on error -- the right-panel notification carries
     * the diagnosis and a "Show console" action the user invokes when
     * (and only when) they want the full log.
     */
    data class Error(val reason: LaunchError) : LaunchLogEvent()
}

/**
 * Why the pre-spawn session refresh did not produce a fresh token.
 *
 * The launch continues regardless -- the previous token is often still good,
 * and refusing to start a game over a refresh that may not have mattered would
 * cost more launches than it saves. What the outcome does decide is how the
 * UI phrases it, because the two failures need different advice: a server that
 * rejected the credentials will reject the game's join too ("Failed to verify
 * username" once the client is already in the multiplayer menu, with nothing
 * pointing back at the launcher), while a server nothing could reach says
 * nothing about whether the token in hand still works.
 */
enum class AuthRefreshFailure {
    /** The auth server answered and refused: bad credentials, dead session, locked account. */
    Rejected,

    /** Nothing reached the auth server (DNS, connect, reset, TLS), so the credentials were never judged. */
    Unreachable,

    /** Anything else -- an unrecognised failure that is not safe to describe as either of the above. */
    Unknown,
}
