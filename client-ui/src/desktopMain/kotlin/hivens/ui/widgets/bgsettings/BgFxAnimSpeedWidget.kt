package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.animspeed", displayName = "widget.bg.fx.animspeed")
@Composable
fun BgFxAnimSpeedWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    LabeledSlider(s.backgroundAnimationSpeed, settings.animationSpeedMultiplier, 0.25f..4f, "%.2fx") {
        ctx.update { copy(animationSpeedMultiplier = it) }
    }
}
