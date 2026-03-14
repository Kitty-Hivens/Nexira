package hivens.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.logic.LaunchState
import hivens.ui.theme.CelestiaTheme
import java.text.DecimalFormat

@Composable
fun LaunchControlPanel(
    state: LaunchState,
    onLaunch: () -> Unit,
    onAbort: () -> Unit,
    onClearError: () -> Unit
) {
    val s = LocalStrings.current

    Column(Modifier.fillMaxWidth()) {

        // ── Status row ────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            when (state) {
                is LaunchState.Idle -> Text(
                    s.launchReady,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary
                )

                is LaunchState.Prepare -> Text(
                    state.stepName,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary
                )

                is LaunchState.Downloading -> {
                    val fmt   = DecimalFormat("#0.0")
                    val dlMb  = state.downloadedBytes / 1024.0 / 1024.0
                    val totMb = state.totalBytes      / 1024.0 / 1024.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${s.launchDownloading} ",
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary
                        )
                        Text(
                            "${fmt.format(dlMb)} / ${fmt.format(totMb)} MB (${state.speedStr})",
                            style      = MaterialTheme.typography.bodySmall,
                            color      = CelestiaTheme.colors.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is LaunchState.Error -> Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.error
                )

                is LaunchState.GameRunning -> Text(
                    s.launchRunning,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.success
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── Progress bar ──────────────────────────────────────────────────────
        val progress = when (state) {
            is LaunchState.Prepare     -> state.progress
            is LaunchState.Downloading -> state.progress
            is LaunchState.GameRunning -> 1.0f
            else                       -> 0f
        }

        if (state !is LaunchState.Idle && state !is LaunchState.Error) {
            LinearProgressIndicator(
                progress        = { progress },
                modifier        = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color           = CelestiaTheme.colors.primary,
                trackColor      = CelestiaTheme.colors.surface,
                gapSize         = 0.dp,
                drawStopIndicator = {}
            )
        } else {
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(16.dp))

        // ── Action button ─────────────────────────────────────────────────────
        val btnText = when (state) {
            is LaunchState.Downloading, is LaunchState.Prepare -> s.launchAbort
            is LaunchState.GameRunning                         -> s.launchRunning.uppercase()
            is LaunchState.Error                               -> s.launchResetError
            else                                               -> s.launchButton
        }

        CelestiaButton(
            text    = btnText,
            enabled = state !is LaunchState.GameRunning,
            // Pulse when ready to play
            glowing = state is LaunchState.Idle,
            onClick = {
                when (state) {
                    is LaunchState.Downloading, is LaunchState.Prepare -> onAbort()
                    is LaunchState.Error                               -> onClearError()
                    else                                               -> onLaunch()
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        )
    }
}
