package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.saturation", displayName = "widget.bg.fx.saturation")
@Composable
fun BgFxSaturationWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundSaturation,
        range  = -1f..1f,
        read   = { saturation },
        format = { "%+.0f%%".format(it * 100) },
        write  = { copy(saturation = it) },
    )
}
