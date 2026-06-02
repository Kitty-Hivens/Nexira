package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.vignette", displayName = "widget.bg.fx.vignette")
@Composable
fun BgFxVignetteWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    LabeledSlider(s.backgroundVignette, settings.vignetteIntensity, 0f..1f, "%.0f%%", 100f) {
        ctx.update { copy(vignetteIntensity = it) }
    }
}
