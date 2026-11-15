package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.nexiraStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.blur", displayName = "widget.bg.fx.blur", needs = ["background.settings", "background.update"])
@Composable
fun BgFxBlurWidget() {
    val s = nexiraStrings()
    BgSlider(
        label  = s.text("backgroundBlur"),
        range  = 0f..25f,
        read   = { blurRadius },
        format = { "%.0f px".format(it) },
        write  = { copy(blurRadius = it) },
    )
}
