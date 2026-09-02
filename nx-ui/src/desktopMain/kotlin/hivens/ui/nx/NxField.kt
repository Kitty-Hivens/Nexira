package hivens.ui.nx

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/**
 * One text input: a [BasicTextField] inside a sunken library surface (opaque body
 * + bevel, no wallpaper bleed) with a [placeholder] and a palette cursor. The one
 * field screens compose, replacing raw `BasicTextField` + a hand-rolled background.
 * [singleLine] off makes it a multi-line area; pass height through [modifier].
 * [textStyle] null falls back to bodySmall in the primary text colour.
 */
@Composable
fun NxField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    textStyle: TextStyle? = null,
) {
    val ts = textStyle ?: MaterialTheme.typography.bodySmall.copy(color = NxTheme.colors.textPrimary)
    NxSurface(
        level    = NxSurfaceLevel.Sunken,
        blurDp   = 0f,
        shape    = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = singleLine,
            textStyle     = ts,
            cursorBrush   = SolidColor(NxTheme.colors.primary),
            modifier      = Modifier.fillMaxWidth().padding(horizontal = Spacing.s10, vertical = Spacing.s8),
        ) { inner ->
            if (value.isEmpty()) {
                Text(placeholder, style = ts.copy(color = NxTheme.colors.textSecondary.copy(alpha = 0.6f)))
            }
            inner()
        }
    }
}
