package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.background.BackgroundLoopMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.widget.model.Widget

@Widget(id = "bg.loop.mode", displayName = "widget.bg.loop.mode", needs = ["background.settings", "background.update"])
@Composable
fun BgLoopModeWidget() {
    val bg = rememberBgSettings() ?: return
    val s = LocalStrings.current
    val settings = bg.value

    val modes = listOf(
        BackgroundLoopMode.UseCodec    to s.backgroundLoopUseCodec,
        BackgroundLoopMode.LoopForever to s.backgroundLoopForever,
        BackgroundLoopMode.PlayOnce    to s.backgroundLoopOnce,
    )
    BgPicker(s.backgroundLoopMode) {
        modes.forEach { (mode, label) ->
            NxChoiceChip(
                label    = label,
                selected = settings.loopMode == mode,
                onToggle = { bg.update { withLoopMode(mode) } },
            )
        }
    }
}
