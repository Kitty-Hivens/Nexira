package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.background.ScaleMode
import hivens.ui.i18n.nexiraStrings
import hivens.ui.nx.NxChoiceChip
import hivens.widget.model.Widget

@Widget(id = "bg.scale.mode", displayName = "widget.bg.scale.mode", needs = ["background.settings", "background.update"])
@Composable
fun BgScaleModeWidget() {
    val bg = rememberBgSettings() ?: return
    val s = nexiraStrings()
    val settings = bg.value

    val modes = listOf(
        ScaleMode.COVER    to s.text("backgroundScaleCover"),
        ScaleMode.CONTAIN  to s.text("backgroundScaleContain"),
        ScaleMode.STRETCH  to s.text("backgroundScaleStretch"),
        ScaleMode.ORIGINAL to s.text("backgroundScaleOriginal"),
        ScaleMode.TILE     to s.text("backgroundScaleTile"),
    )
    BgPicker(s.text("backgroundSectionScale")) {
        modes.forEach { (mode, label) ->
            NxChoiceChip(
                label    = label,
                selected = settings.scaleMode == mode,
                onToggle = { bg.update { withScaleMode(mode) } },
            )
        }
    }
}
