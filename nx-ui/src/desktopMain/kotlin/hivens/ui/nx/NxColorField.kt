package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import hivens.ui.theme.Form
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * Parse a hex string into a [Color] or null. Accepts `#RRGGBB` / `#AARRGGBB`
 * (the `#` and surrounding space optional); any other length, or a non-hex body,
 * is null. One format, no 16-bit guessing -- the rule [NxColorField] enforces.
 */
internal fun parseHexOrNull(hex: String): Color? {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6 && clean.length != 8) return null
    val full = if (clean.length == 6) "FF$clean" else clean
    return runCatching { Color(full.toLong(16)) }.getOrNull()
}

/**
 * A colour input: a swatch + a single hex [NxField] + an optional clear-to-default
 * affordance. One format, validated by [parseHexOrNull] -- the library owns the
 * "what is a colour" rule so no screen re-implements hex vs 16-bit guessing (D13).
 * [hex] null/blank shows the swatch as outline-only (the default); [onValueChange]
 * emits the raw text (null when blank); [onClear] + [clearLabel] reset to default.
 */
@Composable
fun NxColorField(
    hex: String?,
    onValueChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
    clearLabel: String? = null,
    placeholder: String = "#RRGGBB",
) {
    var text by remember(hex) { mutableStateOf(hex.orEmpty()) }
    val parsed = text.takeIf { it.isNotBlank() }?.let(::parseHexOrNull)
    Row(
        modifier              = modifier,
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s10),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(parsed ?: Color.Transparent)
                .border(1.dp, NxTheme.colors.outline, RoundedCornerShape(6.dp)),
        )
        NxField(
            value         = text,
            onValueChange = { v -> text = v; onValueChange(v.ifBlank { null }) },
            placeholder   = placeholder,
            modifier      = Modifier.width(120.dp),
        )
        if (onClear != null && clearLabel != null) {
            Text(
                text     = clearLabel,
                style    = MaterialTheme.typography.labelSmall,
                color    = NxTheme.colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(Form.buttonCorner))
                    .clickable { text = ""; onClear() }
                    .padding(horizontal = Spacing.s8, vertical = Spacing.s4),
            )
        }
    }
}
