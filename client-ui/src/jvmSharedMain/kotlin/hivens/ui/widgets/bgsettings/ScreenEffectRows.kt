package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxSlider
import hivens.ui.theme.NxInk
import hivens.ui.widgets.modules.WidgetModules
import org.koin.compose.getKoin

/**
 * The choice of a screen effect a module brought, and how strongly it is drawn.
 *
 * Absent altogether while no module carries one and none is chosen: it is a power user's control,
 * and a row offering something nothing can answer is clutter. A choice whose module is off keeps
 * its chip, so it can be seen and put back to Off.
 */
@Composable
internal fun ScreenEffectRows(
    selected: String?,
    onSelected: (String?) -> Unit,
    intensity: Float,
    onIntensity: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val effects = getKoin().getOrNull<WidgetModules>()?.state?.collectAsState()?.value?.effects.orEmpty()
    if (effects.isEmpty() && selected == null) return

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BgPicker(s.screenEffectTitle) {
            NxChoiceChip(s.screenEffectOff, selected == null) { onSelected(null) }
            effects.forEach { effect ->
                NxChoiceChip(effect.displayName, selected == effect.id) { onSelected(effect.id) }
            }
            // A choice whose module is off or gone stays visible, named by its id.
            if (selected != null && effects.none { it.id == selected }) {
                NxChoiceChip(selected, true) { onSelected(selected) }
            }
        }
        if (selected != null) {
            NxSlider(
                label         = s.screenEffectStrength,
                value         = intensity,
                range         = 0f..1f,
                valueText     = "${(intensity * 100).toInt()}%",
                onValueChange = onIntensity,
                compact       = true,
            )
            Text(
                text  = s.screenEffectWarning,
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }
    }
}
