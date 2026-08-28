package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.position.y", displayName = "widget.bg.position.y")
@Composable
fun BgPositionYWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundAlignY,
        range  = 0f..1f,
        read   = { alignY },
        format = { "%.2f".format(it) },
        write  = { copy(alignY = it) },
    )
}
