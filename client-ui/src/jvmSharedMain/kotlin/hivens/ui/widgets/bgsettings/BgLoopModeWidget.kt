package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.background.BackgroundLoopMode
import hivens.ui.i18n.nexiraStrings
import hivens.ui.nx.NxChoiceChip
import hivens.widget.model.Widget

@Widget(id = "bg.loop.mode", displayName = "widget.bg.loop.mode", needs = ["background.settings", "background.update"])
@Composable
fun BgLoopModeWidget() {
    val bg = rememberBgSettings() ?: return
    val s = nexiraStrings()
    val settings = bg.value

    val modes = listOf(
        BackgroundLoopMode.UseCodec    to s.text("backgroundLoopUseCodec"),
        BackgroundLoopMode.LoopForever to s.text("backgroundLoopForever"),
        BackgroundLoopMode.PlayOnce    to s.text("backgroundLoopOnce"),
    )
    BgPicker(s.text("backgroundLoopMode")) {
        modes.forEach { (mode, label) ->
            NxChoiceChip(
                label    = label,
                selected = settings.loopMode == mode,
                onToggle = { bg.update { withLoopMode(mode) } },
            )
        }
    }
}
