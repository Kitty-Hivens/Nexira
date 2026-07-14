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
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.reset", displayName = "widget.bg.reset")
@Composable
fun BgResetWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    var confirming by remember { mutableStateOf(false) }

    NxButton(
        label    = s.backgroundReset,
        icon     = NxIcon.RestartAlt,
        style    = NxButtonStyle.Secondary,
        onClick  = { confirming = true },
        modifier = Modifier.fillMaxWidth(),
    )

    if (confirming) {
        DestructiveConfirmDialog(
            title        = s.backgroundResetConfirmTitle,
            body         = s.backgroundResetConfirmBody,
            confirmLabel = s.backgroundReset,
            onConfirm    = { ctx.update { BackgroundSettings() } },
            onDismiss    = { confirming = false },
        )
    }
}
