package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.opacity", displayName = "widget.bg.fx.opacity")
@Composable
fun BgFxOpacityWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundOpacity,
        range  = 0.1f..1f,
        read   = { opacity },
        format = { "%.0f%%".format(it * 100) },
        write  = { copy(opacity = it) },
    )
}
