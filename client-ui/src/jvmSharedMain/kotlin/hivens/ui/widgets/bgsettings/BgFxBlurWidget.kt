package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.blur", displayName = "widget.bg.fx.blur")
@Composable
fun BgFxBlurWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundBlur,
        range  = 0f..25f,
        read   = { blurRadius },
        format = { "%.0f px".format(it) },
        write  = { copy(blurRadius = it) },
    )
}
