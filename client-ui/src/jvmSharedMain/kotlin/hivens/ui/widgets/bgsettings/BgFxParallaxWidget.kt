package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.parallax", displayName = "widget.bg.fx.parallax")
@Composable
fun BgFxParallaxWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundParallax,
        range  = 0f..1f,
        read   = { parallaxIntensity },
        format = { "%.0f%%".format(it * 100) },
        write  = { copy(parallaxIntensity = it) },
    )
}
