package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.saturation", displayName = "widget.bg.fx.saturation")
@Composable
fun BgFxSaturationWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val v = settings.saturation
    NxSlider(
        label = s.backgroundSaturation,
        value = v,
        range = -1f..1f,
        valueText = "%+.0f%%".format(v * 100),
        onValueChange = { ctx.update { copy(saturation = it) } },
    )
}
