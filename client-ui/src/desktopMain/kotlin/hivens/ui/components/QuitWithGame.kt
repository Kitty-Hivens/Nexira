package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the question about a running game is waiting to be answered on quit.
 *
 * A holder rather than screen state because a quit arrives from outside any
 * composition: the tray menu, the window's close button with no tray to hide into.
 */
class QuitGate {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun dismiss() {
        _pending.value = false
    }
}

/**
 * Asks what to do with a running game before the launcher quits.
 *
 * Quitting used to ask nothing: the game kept running with its output readers
 * gone and its session never recorded, while the shutdown hook's documentation
 * claimed the launch was being wound down. Both answers are honoured now, and
 * both record the session first. Leaving the game running is the one that loses
 * nothing in the world, so it comes first; stopping it takes the game's own
 * shutdown time.
 *
 * MUST be composed inside [hivens.ui.theme.NxTheme]: a `Dialog` gets its own
 * composition, and one raised from outside the theme finds no colours.
 */
@Composable
fun QuitWithGameHost(
    gate: QuitGate,
    packName: String?,
    onLeaveRunning: () -> Unit,
    onStopGame: () -> Unit,
) {
    val pending by gate.pending.collectAsState()
    if (!pending) return
    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = gate::dismiss,
        title = { Text(s.quitGameTitle) },
        text  = { Text(s.quitGameBody(packName.orEmpty()), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { gate.dismiss(); onLeaveRunning() }) {
                    Text(s.quitLeaveGame, color = NxTheme.colors.primary)
                }
                TextButton(onClick = { gate.dismiss(); onStopGame() }) {
                    Text(s.quitStopGame, color = NxTheme.colors.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = gate::dismiss) { Text(s.editorCancel) }
        },
        containerColor = NxTheme.colors.surface,
    )
}
