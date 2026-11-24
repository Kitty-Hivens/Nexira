package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.nexiraStrings
import hivens.ui.nx.NxAlertDialog
import hivens.ui.press.TextButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status

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
 * MUST be composed inside [hivens.ui.theme.NxTheme]: the dialog is a layer of the
 * window that takes its theme from where it is opened, and one raised from outside
 * the theme finds none of what the theme sets.
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
    val s = nexiraStrings()
    // Material draws the dialog body, so the plane its words sit on is named by hand: the
    // dialog step of the ladder, which is what a modal is anywhere else in the app.
    val body = NxColor.depth(NxColor.floating)
    NxAlertDialog(
        onDismissRequest = gate::dismiss,
        paneTitle        = s.text("quitGameTitle"),
        title = { OnFill(body) { Text(s.text("quitGameTitle"), color = NxInk.main) } },
        text  = {
            OnFill(body) {
                Text(s.text("quitGameBody", packName.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet)
            }
        },
        confirmButton = {
            OnFill(body) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { gate.dismiss(); onLeaveRunning() }) {
                        Text(s.text("quitLeaveGame"), color = NxColor.lead())
                    }
                    TextButton(onClick = { gate.dismiss(); onStopGame() }) {
                        Text(s.text("quitStopGame"), color = NxColor.status(Status.Error))
                    }
                }
            }
        },
        dismissButton = {
            OnFill(body) {
                TextButton(onClick = gate::dismiss) { Text(s.text("editorCancel"), color = NxColor.lead()) }
            }
        },
        containerColor = body,
    )
}
