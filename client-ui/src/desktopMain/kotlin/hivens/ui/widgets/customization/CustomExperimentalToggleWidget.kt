package hivens.ui.widgets.customization

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.i18n.LocalStrings
import hivens.ui.components.NxSwitch
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Master switch for the experimental color + shape overrides. When
// off, the colors + shape slots unmount entirely so the user sees a
// minimal screen. Removing this widget from the editor leaves the
// experimental flag stuck at its last value; reset-to-default
// brings the toggle back.
@Widget(id = "customization.experimental.toggle", displayName = "widget.customization.experimental.toggle")
@Composable
fun CustomExperimentalToggleWidget(instance: WidgetInstance) {
    val ctx = LocalCustomizationContext.current
    val s = LocalStrings.current
    val settings by ctx.settings

    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text       = s.customizationExperimentalToggle,
                fontWeight = FontWeight.Bold,
                color      = CelestiaTheme.colors.textPrimary,
            )
            Text(
                text  = s.customizationExperimentalSub,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        }
        NxSwitch(
            checked         = settings.experimentalColorOverridesEnabled,
            onCheckedChange = { ctx.update { copy(experimentalColorOverridesEnabled = it) } },
        )
    }
}
