package hivens.ui.widgets.customization

import hivens.ui.theme.LocalMonoFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.customization.sliderKeyboardAdjust
import hivens.ui.theme.NxTheme
import hivens.ui.widgets.toWidgetColorOrNull

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text          = text,
        style         = MaterialTheme.typography.labelSmall,
        fontWeight    = FontWeight.Bold,
        color         = NxTheme.colors.primary,
        letterSpacing = 1.sp,
    )
}

@Composable
internal fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    displayMultiplier: Float = 1f,
    keyStep: Float = (range.endInclusive - range.start) / 100f,
    onValueChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = NxTheme.colors.textSecondary,
            modifier = Modifier.width(150.dp),
        )
        // Box owns hover-focus + arrow keys (fine adjustment); the Slider keeps
        // the pointer drag.
        Box(Modifier.weight(1f).sliderKeyboardAdjust(value, range, keyStep, onValueChange)) {
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
        Text(
            text     = format.format(value * displayMultiplier),
            style    = MaterialTheme.typography.labelSmall,
            color    = NxTheme.colors.textSecondary.copy(alpha = 0.6f),
            modifier = Modifier.width(54.dp),
        )
    }
}

@Composable
internal fun ColorRoleRow(
    role: String,
    currentHex: String?,
    invalidLabel: String,
    onValidHex: (String) -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text       = role.replaceFirstChar { it.uppercase() },
            modifier   = Modifier.width(100.dp),
            color      = NxTheme.colors.textSecondary,
            style      = MaterialTheme.typography.bodySmall,
            fontFamily = LocalMonoFamily.current,
        )
        HexField(
            initialHex   = currentHex ?: "",
            invalidLabel = invalidLabel,
            onValidHex   = onValidHex,
            modifier     = Modifier.weight(1f),
        )
        if (currentHex != null) {
            OutlinedButton(
                onClick        = onClear,
                shape          = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) { Text("x", fontSize = 12.sp) }
        }
    }
}

@Composable
internal fun HexField(
    initialHex: String,
    invalidLabel: String,
    onValidHex: (String) -> Unit,
    modifier: Modifier = Modifier,
    rgbOnly: Boolean = false,
) {
    var text by remember(initialHex) { mutableStateOf(initialHex) }
    val parsed = text.toWidgetColorOrNull()
    // rgbOnly rejects the 8-digit AARRGGBB form, so an RGB-only field stays at
    // exactly six hex digits; callers that allow alpha leave it false.
    fun fits(s: String): Boolean =
        s.trim().removePrefix("#").length.let { if (rgbOnly) it == 6 else it == 6 || it == 8 }
    val valid  = text.isBlank() || (parsed != null && fits(text))

    Row(
        modifier              = modifier,
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parsed?.takeIf { valid } ?: NxTheme.colors.surface)
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp)),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(MaterialTheme.shapes.small)
                .background(glassSurfaceAlpha(0.4f))
                .border(
                    width = 1.dp,
                    color = if (valid) NxTheme.colors.outline.copy(alpha = 0.3f) else NxTheme.colors.error,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value         = text,
                onValueChange = { t ->
                    text = t
                    if (t.isNotBlank()) {
                        val normalized = t.trim()
                        if (fits(normalized)) normalized.toWidgetColorOrNull()?.let { onValidHex(normalized) }
                    }
                },
                singleLine    = true,
                textStyle     = TextStyle(
                    color      = NxTheme.colors.textPrimary,
                    fontFamily = LocalMonoFamily.current,
                    fontSize   = 13.sp,
                ),
                cursorBrush   = SolidColor(NxTheme.colors.primary),
                modifier      = Modifier.fillMaxWidth(),
            )
        }
        if (!valid) {
            Text(invalidLabel, color = NxTheme.colors.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}
