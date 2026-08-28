package hivens.ui.widgets.bgsettings

import androidx.compose.runtime.Composable
import hivens.ui.i18n.LocalStrings
import hivens.widget.model.Widget

@Widget(id = "bg.fx.animspeed", displayName = "widget.bg.fx.animspeed")
@Composable
fun BgFxAnimSpeedWidget() {
    val s = LocalStrings.current
    BgSlider(
        label  = s.backgroundAnimationSpeed,
        range  = 0.25f..4f,
        read   = { animationSpeedMultiplier },
        format = { "%.2fx".format(it) },
        write  = { copy(animationSpeedMultiplier = it) },
    )
}
