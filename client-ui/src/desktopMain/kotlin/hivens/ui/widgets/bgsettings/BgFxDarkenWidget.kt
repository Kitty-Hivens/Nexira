package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.darken", displayName = "widget.bg.fx.darken")
@Composable
fun BgFxDarkenWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundDarken,
        range  = 0f..0.9f,
        read   = { darkenAmount },
        format = { "%.0f%%".format(it * 100) },
        write  = { copy(darkenAmount = it) },
    )
}
