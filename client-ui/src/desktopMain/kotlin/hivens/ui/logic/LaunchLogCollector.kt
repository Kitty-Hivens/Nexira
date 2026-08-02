package hivens.ui.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import hivens.core.data.LauncherLogType
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchLogEvent
import hivens.launcher.launch.LauncherController
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.flow.SharedFlow

// Launcher-emitted provisioning progress: an allowlisted leading word
// + "<current>/<total>[: detail]". Capture group 1 is the collapse slot
// key. The word is allowlisted (not any [A-Za-z]+) on purpose: game /
// mod stdout prints bare progress like "Loading 5/100" or "Baking 4/16"
// that would otherwise be folded into a slot AND dropped from the
// archive (appendOrUpdate does not mirror to the session file). Only
// the launcher's own streams belong here; add new prefixes as they are
// introduced launcher-side.
private val PROGRESS_LINE = Regex("""^(Runtime) \d+/\d+(:.*)?$""")

/**
 * Drains [LauncherController.events] into [GameConsoleService] with
 * localized text. One process-wide collector lives in `Main.kt`'s
 * `application { ... }` block so events fire even when the dashboard
 * (where launches are initiated) is not the current screen.
 *
 * Strings come from [LocalStrings] (the Compose CompositionLocal) wrapped
 * in [rememberUpdatedState] so a runtime locale switch reaches the
 * (non-Composable) `collect` lambda without restarting the LaunchedEffect:
 * latest locale wins at read time.
 *
 * Severity mapping: `LauncherLogType` is the wire enum from `client-core`,
 * `LogType` is the UI enum the console renders.
 */
@Composable
fun LaunchLogCollector(
    events: SharedFlow<LaunchLogEvent>,
    gameConsole: GameConsoleService,
) {
    val currentStrings = rememberUpdatedState(LocalStrings.current)

    LaunchedEffect(events, gameConsole) {
        events.collect { event ->
            val s = currentStrings.value
            when (event) {
                is LaunchLogEvent.SessionStarted -> gameConsole.startSession(event.targetId, event.targetLabel)
                is LaunchLogEvent.AppBanner -> gameConsole.append("${s.appName}...", LogType.INFO)
                is LaunchLogEvent.TargetServer -> gameConsole.append(
                    "-> ${event.name}" + if (event.offline) " [OFFLINE]" else "",
                    LogType.INFO,
                )
                is LaunchLogEvent.OfflineSkipAuth -> gameConsole.append(s.stateOfflineSkipAuth, LogType.WARN)
                is LaunchLogEvent.AuthSucceeded -> gameConsole.append(s.authSuccess(event.uuid), LogType.INFO)
                is LaunchLogEvent.NoPassword -> gameConsole.append(s.stateNoPassword, LogType.WARN)
                is LaunchLogEvent.AuthFailed -> gameConsole.append(
                    "${s.stateAuthFail}: ${event.message ?: ""}",
                    LogType.WARN,
                )
                is LaunchLogEvent.ForeignContentRemoved -> gameConsole.append(
                    s.stateForeignContentRemoved(event.paths.size, event.paths.joinToString(", ")),
                    LogType.WARN,
                )
                is LaunchLogEvent.OfflineSkipSync -> gameConsole.append(s.stateOfflineSkipSync, LogType.INFO)
                is LaunchLogEvent.Launching -> gameConsole.append(s.stateLaunching, LogType.INFO)
                is LaunchLogEvent.ProcessOutput -> {
                    val uiType = when (event.severity) {
                        LauncherLogType.INFO  -> LogType.INFO
                        LauncherLogType.WARN  -> LogType.WARN
                        LauncherLogType.ERROR -> LogType.ERROR
                    }
                    // Collapse high-frequency provisioning progress
                    // ("Runtime 256/1342: <hash>") into a single mutable
                    // line instead of one console entry per item. The
                    // format is the launcher's own (game stdout starts
                    // with "[Thread/LEVEL]:", never "Runtime N/M"), so
                    // matching it is unambiguous. The captured slot key
                    // is the leading word, so a future "Asset N/M" /
                    // "Library N/M" stream folds the same way.
                    val progress = PROGRESS_LINE.matchEntire(event.text)
                    if (progress != null) {
                        gameConsole.appendOrUpdate("progress.${progress.groupValues[1]}", event.text, uiType)
                    } else {
                        gameConsole.append(event.text, uiType)
                    }
                }
                is LaunchLogEvent.Error -> gameConsole.append(localizeError(event.reason, s), LogType.ERROR)
            }
        }
    }
}

/**
 * Localizes a [LaunchError] for the console pane. Private (not shared) so the
 * console copy can diverge from the notification driver's phrasing. Strings are
 * passed in (not read from a global) so the caller controls the locale snapshot.
 */
private fun localizeError(error: LaunchError, s: AppStrings): String = when (error) {
    is LaunchError.ExitCode             -> s.stateExitCode(error.code)
    is LaunchError.Internal             -> s.stateError(error.message)
    is LaunchError.OfflineNoClient      -> s.stateOfflineNoClient
    is LaunchError.OfflineNoManifest    -> s.stateOfflineNoManifest
    is LaunchError.TwoFactorExpired     -> s.auth2faExpired
    is LaunchError.HelperUnavailable    -> s.stateHelperUnavailable(error.mcVersion)
    is LaunchError.AuthlibUnavailable   -> s.stateAuthlibUnavailable(error.mcVersion)
    is LaunchError.MissingAuthProvider  -> s.stateMissingAuthProvider(error.providerKey)
}
