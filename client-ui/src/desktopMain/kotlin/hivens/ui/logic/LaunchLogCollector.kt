package hivens.ui.logic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import hivens.core.data.LauncherLogType
import hivens.launcher.launch.LaunchError
import hivens.launcher.launch.LaunchLogEvent
import hivens.launcher.launch.LauncherController
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.utils.GameConsoleService
import hivens.ui.utils.LogType
import kotlinx.coroutines.flow.SharedFlow

/**
 * Drains [LauncherController.events] into [GameConsoleService] with
 * localized text. One process-wide collector lives in `Main.kt`'s
 * `application { ... }` block so events fire even when the dashboard
 * (where launches are initiated) is not the current screen.
 *
 * Strings come from [LocalStrings] (the Compose CompositionLocal) wrapped
 * in [rememberUpdatedState] so a runtime locale switch reaches the
 * (non-Composable) `collect` lambda without restarting the LaunchedEffect.
 * Pre-B9 this read a mutable global `I18n.s` which had no thread-safety
 * guarantees; the local-state seam keeps the semantics ("latest locale
 * wins at read time") without the global.
 *
 * Severity mapping mirrors what the controller used to do inline for
 * `ProcessOutput` -- `LauncherLogType` is the wire enum from
 * `client-core`, `LogType` is the UI enum the console renders.
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
                is LaunchLogEvent.SessionStarted -> gameConsole.startSession()
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
                is LaunchLogEvent.OfflineSkipSync -> gameConsole.append(s.stateOfflineSkipSync, LogType.INFO)
                is LaunchLogEvent.Launching -> gameConsole.append(s.stateLaunching, LogType.INFO)
                is LaunchLogEvent.ProcessOutput -> {
                    val uiType = when (event.severity) {
                        LauncherLogType.INFO  -> LogType.INFO
                        LauncherLogType.WARN  -> LogType.WARN
                        LauncherLogType.ERROR -> LogType.ERROR
                    }
                    gameConsole.append(event.text, uiType)
                }
                is LaunchLogEvent.Error -> gameConsole.append(localizeError(event.reason, s), LogType.ERROR)
                is LaunchLogEvent.RequestConsoleVisible -> gameConsole.show()
            }
        }
    }
}

/**
 * Mirrors `localizeError` in `LaunchControlPanel`. Kept here as a private
 * helper rather than shared so the two consumers (status row + console
 * pane) stay independent -- one may want a shorter form than the other
 * in the future. Strings are passed in (not read from a global) so the
 * caller controls which locale snapshot is used.
 */
private fun localizeError(error: LaunchError, s: AppStrings): String = when (error) {
    is LaunchError.ExitCode          -> s.stateExitCode(error.code)
    is LaunchError.Internal          -> s.stateError(error.message)
    is LaunchError.OfflineNoClient   -> s.stateOfflineNoClient
    is LaunchError.OfflineNoManifest -> s.stateOfflineNoManifest
    is LaunchError.TwoFactorExpired  -> s.auth2faExpired
    is LaunchError.AuthFail          -> "${s.stateAuthFail}: ${error.cause ?: ""}"
}
