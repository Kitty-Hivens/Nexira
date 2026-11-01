package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxColorField
import hivens.ui.nx.NxSlider
import hivens.widget.model.Widget

// Tint colour (any hex, via the shared NxColorField) plus an intensity slider that
// only appears once a tint is set -- they share fate, so one widget. Clearing the
// colour ("None") drops the opacity to 0; setting one floors it at a visible 0.3.
@Widget(id = "bg.tint", displayName = "widget.bg.tint")
@Composable
fun BgTintWidget() {
    val ctx = LocalBgSettingsContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle(s.backgroundSectionTint)

        NxColorField(
            hex           = settings.tintColor,
            onValueChange = { hex ->
                ctx.update { copy(tintColor = hex, tintOpacity = if (hex != null) tintOpacity.coerceAtLeast(0.3f) else 0f) }
            },
            onClear       = { ctx.update { copy(tintColor = null, tintOpacity = 0f) } },
            clearLabel    = s.backgroundTintNone,
        )

        if (settings.tintColor != null) {
            val v = settings.tintOpacity
            NxSlider(
                label         = s.backgroundTintIntensity,
                value         = v,
                range         = 0f..0.7f,
                valueText     = "%.0f%%".format(v * 100),
                onValueChange = { ctx.update { copy(tintOpacity = it) } },
            )
        }
    }
}
