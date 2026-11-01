package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import hivens.ui.surface.NxSurface
import hivens.ui.theme.Spacing
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.surface.SurfaceKind

/**
 * One text input: a [BasicTextField] inside a [SurfaceKind.Field] surface with a
 * [placeholder] and a cursor in the theme's lead colour. The one
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
    NxSurface(
        kind     = SurfaceKind.Field,
        shape    = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        // Inside the surface, so the ink is the one that reads on the field itself.
        val ts = textStyle ?: MaterialTheme.typography.bodySmall.copy(color = NxInk.main)
        BasicTextField(
            value         = value,
            onValueChange = onValueChange,
            singleLine    = singleLine,
            textStyle     = ts,
            cursorBrush   = SolidColor(NxColor.lead()),
            modifier      = Modifier.fillMaxWidth(),
        ) { inner ->
            // The inset is inside the field, not around it. Around it, a press on the
            // field's own edge landed on nothing, and to the shell it was a press
            // outside the field, which let go of the caret.
            Box(Modifier.padding(horizontal = Spacing.s10, vertical = Spacing.s8)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = ts.copy(color = NxInk.quiet))
                }
                inner()
            }
        }
    }
}
