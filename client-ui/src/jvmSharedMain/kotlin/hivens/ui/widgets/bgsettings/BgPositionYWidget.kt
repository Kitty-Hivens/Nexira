package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.nexiraStrings
import hivens.widget.model.Widget

@Widget(id = "bg.position.y", displayName = "widget.bg.position.y", needs = ["background.settings", "background.update"])
@Composable
fun BgPositionYWidget() {
    val s = nexiraStrings()
    BgSlider(
        label  = s.text("backgroundAlignY"),
        range  = 0f..1f,
        read   = { alignY },
        format = { "%.2f".format(it) },
        write  = { copy(alignY = it) },
    )
}
