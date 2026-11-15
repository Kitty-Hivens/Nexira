package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.nexiraStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.parallax", displayName = "widget.bg.fx.parallax", needs = ["background.settings", "background.update"])
@Composable
fun BgFxParallaxWidget() {
    val s = nexiraStrings()
    BgSlider(
        label  = s.text("backgroundParallax"),
        range  = 0f..1f,
        read   = { parallaxIntensity },
        format = { "%.0f%%".format(it * 100) },
        write  = { copy(parallaxIntensity = it) },
    )
}
