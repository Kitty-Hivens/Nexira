package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxToggle
import hivens.widget.model.Widget

@Widget(id = "bg.enable.toggle", displayName = "widget.bg.enable.toggle", needs = ["background.settings", "background.update"])
@Composable
fun BgEnableToggleWidget() {
    val bg = rememberBgSettings() ?: return
    val s = LocalStrings.current
    val settings = bg.value

    NxToggle(
        label           = s.backgroundEnable,
        checked         = settings.enabled,
        icon            = NxIcon.Wallpaper,
        onCheckedChange = { bg.update { copy(enabled = it) } },
    )
}
