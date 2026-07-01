package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.parallax", displayName = "widget.bg.fx.parallax")
@Composable
fun BgFxParallaxWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val v = settings.parallaxIntensity
    NxSlider(
        label = s.backgroundParallax,
        value = v,
        range = 0f..1f,
        valueText = "%.0f%%".format(v * 100),
        onValueChange = { ctx.update { copy(parallaxIntensity = it) } },
    )
}
