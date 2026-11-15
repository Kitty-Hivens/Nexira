package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import hivens.ui.background.BackgroundSettings
import hivens.ui.components.DestructiveConfirmDialog
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.widgets.Commands
import hivens.widget.api.rememberScopedCommand
import hivens.widget.model.Widget

@Widget(id = "bg.reset", displayName = "widget.bg.reset", needs = ["background.update"])
@Composable
fun BgResetWidget() {
    val update = rememberScopedCommand(Commands.UpdateBackground) ?: return
    val s = nexiraStrings()
    var confirming by remember { mutableStateOf(false) }

    NxButton(
        label    = s.text("backgroundReset"),
        icon     = NxIcon.RestartAlt,
        style    = NxButtonStyle.Secondary,
        onClick  = { confirming = true },
        modifier = Modifier.fillMaxWidth(),
    )

    if (confirming) {
        DestructiveConfirmDialog(
            title        = s.text("backgroundResetConfirmTitle"),
            body         = s.text("backgroundResetConfirmBody"),
            confirmLabel = s.text("backgroundReset"),
            onConfirm    = { update { BackgroundSettings() } },
            onDismiss    = { confirming = false },
        )
    }
}
