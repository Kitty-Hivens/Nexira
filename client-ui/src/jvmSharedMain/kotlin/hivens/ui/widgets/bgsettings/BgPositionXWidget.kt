package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.nexiraStrings
import hivens.widget.model.Widget

@Widget(id = "bg.position.x", displayName = "widget.bg.position.x", needs = ["background.settings", "background.update"])
@Composable
fun BgPositionXWidget() {
    val s = nexiraStrings()
    BgSlider(
        label  = s.text("backgroundAlignX"),
        range  = 0f..1f,
        read   = { alignX },
        format = { "%.2f".format(it) },
        write  = { copy(alignX = it) },
    )
}
