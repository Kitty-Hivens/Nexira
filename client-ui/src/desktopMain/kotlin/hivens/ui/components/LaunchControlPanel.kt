package hivens.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.launch.LaunchState
import hivens.core.launch.PrepareStage
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxTheme
import java.text.DecimalFormat

@Composable
fun LaunchControlPanel(
    state: LaunchState,
    onLaunch: () -> Unit,
    onAbort: () -> Unit,
) {
    val s = LocalStrings.current
    val af = LocalAprilFools.current

    // Reset the April Fools progress regression whenever we leave the
    // Downloading phase. The reset used to live inside LauncherController
    // (immediately after `processSession` returned); now that the
    // controller is free of UI-easter-egg dependencies, the UI owns the
    // lifecycle. Idempotent -- safe to fire on any non-Downloading state.
    val isDownloading = state is LaunchState.Downloading
    LaunchedEffect(isDownloading) {
        if (!isDownloading) af.resetProgress()
    }

    Column(Modifier.fillMaxWidth()) {

        // -- Status row -----------------------------------------------------
        Row(
            Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            when (state) {
                is LaunchState.Idle -> Text(
                    s.launchReady,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )

                is LaunchState.Prepare -> Text(
                    text  = localizeStage(state.stage, s),
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )

                is LaunchState.Downloading -> {
                    val fmt   = DecimalFormat("#0.0")
                    val dlMb  = state.downloadedBytes / 1024.0 / 1024.0
                    val totMb = state.totalBytes      / 1024.0 / 1024.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${s.launchDownloading} ",
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                        Text(
                            "${fmt.format(dlMb)} / ${fmt.format(totMb)} MB (${formatSpeed(state.speedBytesPerSec, fmt)})",
                            style      = MaterialTheme.typography.bodySmall,
                            color      = NxTheme.colors.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Error is rendered like Idle here: the failure is surfaced by
                // the notification system, not by an in-panel banner. The panel
                // just returns to a ready/playable state.
                is LaunchState.Error -> Text(
                    s.launchReady,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )

                is LaunchState.GameRunning -> Text(
                    s.launchRunning,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.success,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // -- Progress bar ---------------------------------------------------
        // April Fools display regression is applied only on the byte-counter
        // path; coarse prepare progress stays accurate so the user can tell
        // the launcher isn't frozen during the non-download phases.
        val progress = when (state) {
            is LaunchState.Prepare -> state.progress
            is LaunchState.Downloading -> af.wrapProgress(state.downloadedBytes, state.totalBytes)
            is LaunchState.GameRunning -> 1.0f
            else                       -> 0f
        }

        if (state !is LaunchState.Idle && state !is LaunchState.Error) {
            // NaN sentinel from wrapProgress = "size unknown but bytes
            // flowing" -> indeterminate. Otherwise determinate fraction.
            val barModifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
            if (progress.isNaN()) {
                LinearProgressIndicator(
                    modifier        = barModifier,
                    color           = NxTheme.colors.primary,
                    trackColor      = NxTheme.colors.surface,
                )
            } else {
                LinearProgressIndicator(
                    progress        = { progress },
                    modifier        = barModifier,
                    color           = NxTheme.colors.primary,
                    trackColor      = NxTheme.colors.surface,
                    gapSize         = 0.dp,
                    drawStopIndicator = {},
                )
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(16.dp))

        // -- Action button --------------------------------------------------
        // Error maps to Play (not a "clear error" affordance): the next launch
        // attempt overwrites the Error state, and the failure already went out as
        // a notification. So Error behaves exactly like Idle in this panel.
        val btnText = when (state) {
            is LaunchState.Downloading, is LaunchState.Prepare -> s.launchAbort
            is LaunchState.GameRunning                         -> s.launchRunning
            else                                               -> s.launchButton
        }

        if (state is LaunchState.Idle && af.isActive()) {
            // Only the PLAY button in idle state is a chaos target.
            // Abort / Clear-error buttons stay reliable so the game can always be stopped.
            af.ChaosButton(
                id       = "launch_play_btn",
                text     = btnText,
                onClick  = onLaunch,
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )
        } else {
            CelestiaButton(
                text    = btnText,
                enabled = state !is LaunchState.GameRunning,
                // Pulse when ready to play (Idle, or after an error -- which the
                // panel now treats as ready, with the failure shown via notification)
                glowing = state is LaunchState.Idle || state is LaunchState.Error,
                onClick = {
                    when (state) {
                        is LaunchState.Downloading, is LaunchState.Prepare -> onAbort()
                        else                                               -> onLaunch()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
            )
        }
        // Puppet: single action whose semantic depends on the current LaunchState.
        // Same mapping as the CelestiaButton onClick above -- driver doesn't need
        // to know whether it's currently a Play / Abort / ClearError button, just
        // "do the action attached to the launch control".
        PuppetClick("dashboard.launch", enabled = state !is LaunchState.GameRunning) {
            when (state) {
                is LaunchState.Downloading, is LaunchState.Prepare -> onAbort()
                else                                               -> onLaunch()
            }
        }
    }
}

/**
 * Maps a coarse [PrepareStage] to a localized status label. The strings
 * already exist in [hivens.ui.i18n.AppStrings] from when the controller
 * produced text directly; we just consume them at the UI side now.
 */
private fun localizeStage(stage: PrepareStage, s: hivens.ui.i18n.AppStrings): String = when (stage) {
    PrepareStage.INIT   -> s.stateInit
    PrepareStage.AUTH   -> s.stateAuth
    PrepareStage.SYNC   -> s.stateSync
    PrepareStage.JVM    -> s.stateJvm
    PrepareStage.LAUNCH -> s.stateLaunching
}

/**
 * Bytes/second to the unit the UI shows. Formatting lives here (not
 * upstream in the controller) so the user's UI locale wins over the
 * controller's snapshot locale.
 */
private fun formatSpeed(bytesPerSec: Long, fmt: DecimalFormat): String {
    if (bytesPerSec <= 0) return "-- KB/s"
    return when {
        bytesPerSec >= 1_048_576L -> "${fmt.format(bytesPerSec / 1_048_576.0)} MB/s"
        bytesPerSec >= 1_024L     -> "${fmt.format(bytesPerSec / 1_024.0)} KB/s"
        else                      -> "$bytesPerSec B/s"
    }
}
