package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxToggle
import hivens.widget.model.Widget

@Widget(id = "bg.enable.toggle", displayName = "widget.bg.enable.toggle")
@Composable
fun BgEnableToggleWidget() {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    NxToggle(
        label           = s.backgroundEnable,
        checked         = settings.enabled,
        icon            = NxIcon.Wallpaper,
        onCheckedChange = { ctx.update { copy(enabled = it) } },
    )
}
