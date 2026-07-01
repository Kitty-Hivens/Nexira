package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.position.x", displayName = "widget.bg.position.x")
@Composable
fun BgPositionXWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val v = settings.alignX
    NxSlider(
        label = s.backgroundAlignX,
        value = v,
        range = 0f..1f,
        valueText = "%.2f".format(v),
        onValueChange = { ctx.update { copy(alignX = it) } },
    )
}
