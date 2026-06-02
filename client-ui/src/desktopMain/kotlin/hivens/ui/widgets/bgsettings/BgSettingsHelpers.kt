package hivens.ui.widgets.bgsettings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.customization.sliderKeyboardAdjust
import hivens.ui.theme.CelestiaTheme

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text          = text,
        style         = MaterialTheme.typography.labelSmall,
        fontWeight    = FontWeight.Bold,
        color         = CelestiaTheme.colors.primary,
        letterSpacing = 1.sp,
    )
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String = "%.2f",
    displayMultiplier: Float = 1f,
    keyStep: Float = (range.endInclusive - range.start) / 100f,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = CelestiaTheme.colors.textSecondary,
            modifier = Modifier.width(110.dp),
        )
        Box(Modifier.weight(1f).sliderKeyboardAdjust(value, range, keyStep, onValueChange)) {
            Slider(
                value         = value,
                onValueChange = onValueChange,
                valueRange    = range,
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor         = CelestiaTheme.colors.primary,
                    activeTrackColor   = CelestiaTheme.colors.primary,
                    inactiveTrackColor = CelestiaTheme.colors.outline.copy(alpha = 0.2f),
                ),
            )
        }
        Text(
            text     = format.format(value * displayMultiplier),
            style    = MaterialTheme.typography.labelSmall,
            color    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.width(44.dp),
        )
    }
}
