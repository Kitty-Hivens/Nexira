package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.background.ScaleMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.widget.model.Widget

@Widget(id = "bg.scale.mode", displayName = "widget.bg.scale.mode")
@Composable
fun BgScaleModeWidget() {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    val modes = listOf(
        ScaleMode.COVER    to s.backgroundScaleCover,
        ScaleMode.CONTAIN  to s.backgroundScaleContain,
        ScaleMode.STRETCH  to s.backgroundScaleStretch,
        ScaleMode.ORIGINAL to s.backgroundScaleOriginal,
        ScaleMode.TILE     to s.backgroundScaleTile,
    )
    BgPicker(s.backgroundSectionScale) {
        modes.forEach { (mode, label) ->
            NxChoiceChip(
                label    = label,
                selected = settings.scaleMode == mode,
                onToggle = { ctx.update { copy(scaleMode = mode) } },
            )
        }
    }
}
