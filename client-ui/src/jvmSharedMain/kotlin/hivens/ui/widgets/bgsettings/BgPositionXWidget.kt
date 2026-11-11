package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.position.x", displayName = "widget.bg.position.x")
@Composable
fun BgPositionXWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundAlignX,
        range  = 0f..1f,
        read   = { alignX },
        format = { "%.2f".format(it) },
        write  = { copy(alignX = it) },
    )
}
