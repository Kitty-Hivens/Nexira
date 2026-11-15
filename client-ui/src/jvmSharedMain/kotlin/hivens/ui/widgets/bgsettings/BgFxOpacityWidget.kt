package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.nexiraStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.opacity", displayName = "widget.bg.fx.opacity", needs = ["background.settings", "background.update"])
@Composable
fun BgFxOpacityWidget() {
    val s = nexiraStrings()
    BgSlider(
        label  = s.text("backgroundOpacity"),
        range  = 0.1f..1f,
        read   = { opacity },
        format = { "%.0f%%".format(it * 100) },
        write  = { copy(opacity = it) },
    )
}
