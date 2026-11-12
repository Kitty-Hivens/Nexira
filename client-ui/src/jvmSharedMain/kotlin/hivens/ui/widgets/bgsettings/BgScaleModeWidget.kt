package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.background.ScaleMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.widget.model.Widget

@Widget(id = "bg.scale.mode", displayName = "widget.bg.scale.mode", needs = ["background.settings", "background.update"])
@Composable
fun BgScaleModeWidget() {
    val bg = rememberBgSettings() ?: return
    val s = LocalStrings.current
    val settings = bg.value

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
                onToggle = { bg.update { withScaleMode(mode) } },
            )
        }
    }
}
