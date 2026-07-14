package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.fx.vignette", displayName = "widget.bg.fx.vignette")
@Composable
fun BgFxVignetteWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings
    val v = settings.vignetteIntensity
    NxSlider(
        label = s.backgroundVignette,
        value = v,
        range = 0f..1f,
        valueText = "%.0f%%".format(v * 100),
        onValueChange = { ctx.update { copy(vignetteIntensity = it) } },
    )
}
