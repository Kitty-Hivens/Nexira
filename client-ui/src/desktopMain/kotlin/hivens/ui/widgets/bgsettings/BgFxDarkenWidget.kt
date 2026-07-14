package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.darken", displayName = "widget.bg.fx.darken")
@Composable
fun BgFxDarkenWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val v = settings.darkenAmount
    NxSlider(
        label = s.backgroundDarken,
        value = v,
        range = 0f..0.9f,
        valueText = "%.0f%%".format(v * 100),
        onValueChange = { ctx.update { copy(darkenAmount = it) } },
    )
}
