package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.opacity", displayName = "widget.bg.fx.opacity")
@Composable
fun BgFxOpacityWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    LabeledSlider(s.backgroundOpacity, settings.opacity, 0.1f..1f, "%.0f%%", 100f) {
        ctx.update { copy(opacity = it) }
    }
}
