package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.blur", displayName = "widget.bg.fx.blur")
@Composable
fun BgFxBlurWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val v = settings.blurRadius
    NxSlider(
        label = s.backgroundBlur,
        value = v,
        range = 0f..25f,
        valueText = "%.0f px".format(v),
        onValueChange = { ctx.update { copy(blurRadius = it) } },
    )
}
