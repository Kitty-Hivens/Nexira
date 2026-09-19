package hivens.ui.widgets.customization

import hivens.ui.theme.LocalMonoFamily
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.customization.sliderKeyboardAdjust
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSliderTrack
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import hivens.ui.widgets.toWidgetColorOrNull

/**
 * How much of a 320dp panel its labels are allowed to take.
 *
 * One number, because a column of unlike controls only reads as a column while
 * every row measures its label the same way. It used to be 150 on a slider and 140
 * on a field, with a switch on neither, so the panel had three left edges and the
 * eye had to find each control before it could compare any two.
 *
 * 120 rather than the 140 it replaces: the panel has 292dp between its gutters, and
 * half of that spent on a label left a slider 74dp of track, which is a control too
 * short to aim at.
 */
internal val panelLabelWidth: Dp = 120.dp

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
    // The library's row, so a property row and a settings row are one shape. The
    // track and the readout are what this adds to it. The label column, the type and
    // the band are the row's to decide, and they are decided once.
    NxRow(title = label, compact = true, labelWidth = panelLabelWidth) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NxSliderTrack(
                value         = value,
                range         = range,
                onValueChange = onValueChange,
                compact       = true,
                modifier      = Modifier.weight(1f).sliderKeyboardAdjust(value, range, keyStep, onValueChange),
            )
            Text(
                text      = format.format(value * displayMultiplier),
                style     = MaterialTheme.typography.labelSmall,
                color     = NxTheme.colors.textSecondary.copy(alpha = 0.6f),
                textAlign = TextAlign.End,
                maxLines  = 1,
                modifier  = Modifier.width(READOUT_WIDTH).padding(start = Spacing.s6),
            )
        }
    }
}

/** Enough for "100%" and for a two-decimal fraction, which are the two widest readouts. */
private val READOUT_WIDTH = 42.dp

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
                .background(NxTheme.colors.surface.copy(alpha = 0.4f))
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
