package hivens.ui.nx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.customization.sliderKeyboardAdjust
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * A labeled slider whose track is **bounded** so it never runs to the window
 * edge on a wide display (Rule 6/D08). The header row carries [label] and
 * [valueText]; the track sits below, capped at [maxTrackWidth] and left-aligned,
 * with hover + arrow-key fine adjustment. Colours come from the palette, so no
 * call site sets `SliderDefaults.colors` by hand.
 */
@Composable
fun NxSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    keyStep: Float = (range.endInclusive - range.start) / 100f,
    maxTrackWidth: Dp = 420.dp,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Text(
                label,
                color      = NxTheme.colors.textPrimary,
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
                modifier   = Modifier.weight(1f, fill = false).padding(end = Spacing.s8),
            )
            Text(
                valueText,
                style    = MaterialTheme.typography.bodySmall,
                color    = NxTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // widthIn caps the max constraint, fillMaxWidth fills within it: the track
        // grows to the section width but never past maxTrackWidth, so it stops well
        // short of the monitor edge on a 2560 display.
        Box(
            Modifier
                .widthIn(max = maxTrackWidth)
                .fillMaxWidth()
                .sliderKeyboardAdjust(value, range, keyStep, onValueChange),
        ) {
            Slider(
                value         = value,
                onValueChange = onValueChange,
                valueRange    = range,
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor         = NxTheme.colors.primary,
                    activeTrackColor   = NxTheme.colors.primary,
                    inactiveTrackColor = NxTheme.colors.outline.copy(alpha = 0.2f),
                ),
            )
        }
    }
}
