package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.parallax", displayName = "widget.bg.fx.parallax")
@Composable
fun BgFxParallaxWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    LabeledSlider(s.backgroundParallax, settings.parallaxIntensity, 0f..1f, "%.0f%%", 100f) {
        ctx.update { copy(parallaxIntensity = it) }
    }
}
