package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.vignette", displayName = "widget.bg.fx.vignette")
@Composable
fun BgFxVignetteWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundVignette,
        range  = 0f..1f,
        read   = { vignetteIntensity },
        format = { "%.0f%%".format(it * 100) },
        write  = { copy(vignetteIntensity = it) },
    )
}
