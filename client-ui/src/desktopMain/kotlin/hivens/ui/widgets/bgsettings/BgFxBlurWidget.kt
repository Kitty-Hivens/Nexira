package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.blur", displayName = "widget.bg.fx.blur")
@Composable
fun BgFxBlurWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    LabeledSlider(s.backgroundBlur, settings.blurRadius, 0f..25f, "%.0f px") {
        ctx.update { copy(blurRadius = it) }
    }
}
