package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.opacity", displayName = "widget.bg.fx.opacity")
@Composable
fun BgFxOpacityWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val v = settings.opacity
    NxSlider(
        label = s.backgroundOpacity,
        value = v,
        range = 0.1f..1f,
        valueText = "%.0f%%".format(v * 100),
        onValueChange = { ctx.update { copy(opacity = it) } },
    )
}
