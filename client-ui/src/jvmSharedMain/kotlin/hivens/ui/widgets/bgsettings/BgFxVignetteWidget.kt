package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.nexiraStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.vignette", displayName = "widget.bg.fx.vignette", needs = ["background.settings", "background.update"])
@Composable
fun BgFxVignetteWidget() {
    val s = nexiraStrings()
    BgSlider(
        label  = s.text("backgroundVignette"),
        range  = 0f..1f,
        read   = { vignetteIntensity },
        format = { "%.0f%%".format(it * 100) },
        write  = { copy(vignetteIntensity = it) },
    )
}
