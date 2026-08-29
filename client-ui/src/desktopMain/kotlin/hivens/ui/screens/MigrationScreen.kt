package hivens.ui.screens

import hivens.ui.theme.LocalMonoFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.launcher.platform.DataDirMigration
import hivens.ui.surface.NxCard
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/**
 * Mandatory full-screen migration UI shown on first Nexira launch when
 * a non-empty Aura-era data directory is present. Blocks app boot:
 * normal UI does not render until the copy completes (and the user
 * restarts).
 *
 * State machine:
 *   Ready -> InProgress -> Completed | Failed
 *                 ^                       |
 *                 +-----------------------+ (Retry)
 *
 * Closing the window or killing the process before Completed leaves
 * the legacy `.migrated` marker unwritten, so the next launch shows
 * this screen again. Already-copied files are REPLACE_EXISTING'd on
 * retry.
 *
 * Restart-after-completion: Compose state (Koin singletons, cached
 * settings.json contents, etc.) was instantiated against the empty
 * Nexira data dir before migration ran. Re-reading everything in-place
 * is intrusive; quitting and letting the user re-launch is the simple
 * correct path.
 */
@Composable
fun MigrationScreen(
    source: DataDirMigration.Source,
    target: Path,
    onQuit: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var state: UiState by remember { mutableStateOf(UiState.Ready) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NxTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        NxCard(modifier = Modifier.widthIn(min = 480.dp, max = 640.dp)) {
            Column(
                modifier = Modifier.padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (val st = state) {
                    UiState.Ready -> ReadyContent(
                        source = source,
                        target = target,
                        onStart = {
                            state = UiState.InProgress(bytesDone = 0L, currentFile = null)
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    DataDirMigration.migrate(source, target) { done, current ->
                                        state = UiState.InProgress(
                                            bytesDone = done,
                                            currentFile = source.path.relativize(current).toString(),
                                        )
                                    }
                                }
                                state = result.fold(
                                    onSuccess = { UiState.Completed },
                                    onFailure = { UiState.Failed(it.message ?: it::class.simpleName.orEmpty()) },
                                )
                            }
                        },
                    )
                    is UiState.InProgress -> ProgressContent(source = source, state = st)
                    UiState.Completed -> CompletedContent(onQuit = onQuit)
                    is UiState.Failed -> FailedContent(
                        error = st.error,
                        onRetry = { state = UiState.Ready },
                        onQuit = onQuit,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    source: DataDirMigration.Source,
    target: Path,
    onStart: () -> Unit,
) {
    val s = LocalStrings.current
    Text(
        text = s.migrationWelcome,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = NxTheme.colors.textPrimary,
    )
    Text(
        text = s.migrationDescription,
        style = MaterialTheme.typography.bodyMedium,
        color = NxTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(NxTheme.colors.surface.copy(alpha = 0.4f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PathRow(label = s.migrationFromHeader, path = source.path.toString())
        PathRow(label = s.migrationToHeader, path = target.toString())
        Spacer(Modifier.height(4.dp))
        Text(
            text = s.migrationSize(megabytes = (source.totalBytes / 1_048_576L).toInt(), files = source.fileCount),
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
    }
    NxButton(
        label = s.migrationStart,
        onClick = onStart,
        modifier = Modifier.fillMaxWidth(),
        style = NxButtonStyle.Primary,
    )
}

@Composable
private fun ProgressContent(source: DataDirMigration.Source, state: UiState.InProgress) {
    val s = LocalStrings.current
    val totalMb = (source.totalBytes / 1_048_576L).coerceAtLeast(1L).toInt()
    val doneMb = (state.bytesDone / 1_048_576L).toInt()
    val fraction = if (source.totalBytes > 0L) {
        (state.bytesDone.toFloat() / source.totalBytes.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Text(
        text = s.migrationInProgress,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = NxTheme.colors.textPrimary,
    )
    if (state.currentFile != null) {
        Text(
            text = s.migrationCurrentFile(state.currentFile),
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
            fontFamily = LocalMonoFamily.current,
            textAlign = TextAlign.Center,
        )
    }
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = NxTheme.colors.primary,
        trackColor = NxTheme.colors.surface,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
    Text(
        text = s.migrationProgressBytes(doneMb = doneMb, totalMb = totalMb),
        style = MaterialTheme.typography.bodySmall,
        color = NxTheme.colors.textSecondary,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun CompletedContent(onQuit: () -> Unit) {
    val s = LocalStrings.current
    Text(
        text = s.migrationCompletedTitle,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = NxTheme.colors.success,
    )
    Text(
        text = s.migrationCompletedBody,
        style = MaterialTheme.typography.bodyMedium,
        color = NxTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    NxButton(
        label = s.migrationQuit,
        onClick = onQuit,
        modifier = Modifier.fillMaxWidth(),
        style = NxButtonStyle.Primary,
    )
}

@Composable
private fun FailedContent(error: String, onRetry: () -> Unit, onQuit: () -> Unit) {
    val s = LocalStrings.current
    Text(
        text = s.migrationFailedTitle,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = NxTheme.colors.error,
    )
    Text(
        text = s.migrationFailedBody(error),
        style = MaterialTheme.typography.bodyMedium,
        color = NxTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NxButton(
            label = s.migrationRetry,
            onClick = onRetry,
            modifier = Modifier.weight(1f),
            style = NxButtonStyle.Primary,
        )
        NxButton(
            label = s.migrationQuit,
            onClick = onQuit,
            modifier = Modifier.weight(1f),
            style = NxButtonStyle.Secondary,
        )
    }
}

@Composable
private fun PathRow(label: String, path: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = NxTheme.colors.textSecondary,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textPrimary,
            fontFamily = LocalMonoFamily.current,
        )
    }
}

private sealed interface UiState {
    object Ready : UiState
    data class InProgress(val bytesDone: Long, val currentFile: String?) : UiState
    object Completed : UiState
    data class Failed(val error: String) : UiState
}
