package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import hivens.ui.background.BackgroundLoopMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

@Widget(id = "bg.loop.mode", displayName = "widget.bg.loop.mode")
@Composable
fun BgLoopModeWidget(instance: WidgetInstance) {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

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
                onToggle = { ctx.update { copy(loopMode = mode) } },
            )
        }
    }
}
