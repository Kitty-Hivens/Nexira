package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
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
 *
 * The caller keeps the text: [value] in, [onValueChange] out. The field edits a text state of its
 * own and follows the caller both ways (see [rememberFieldState]), so a caller that rewrites or
 * refuses an edit sees the field show what it kept.
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
    val state = rememberFieldState(value, onValueChange)

    NxSurface(
        kind     = SurfaceKind.Field,
        shape    = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        // Inside the surface, so the ink is the one that reads on the field itself.
        val ts = textStyle ?: MaterialTheme.typography.bodySmall.copy(color = NxInk.main)
        BasicTextField(
            state       = state,
            lineLimits  = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
            textStyle   = ts,
            cursorBrush = SolidColor(NxColor.lead()),
            modifier    = Modifier.fillMaxWidth(),
            // The inset is inside the field, not around it. Around it, a press on the
            // field's own edge landed on nothing, and to the shell it was a press
            // outside the field, which let go of the caret.
            decorator   = TextFieldDecorator { inner ->
                Box(Modifier.padding(horizontal = Spacing.s10, vertical = Spacing.s8)) {
                    if (state.text.isEmpty()) {
                        Text(placeholder, style = ts.copy(color = NxInk.quiet))
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * The text state a field edits, kept in step with the caller's string.
 *
 * The caller changed the text from outside: the state follows. Typing goes to the caller, and a
 * frame later the caller's answer is read back, because an edit it did not keep would otherwise
 * stay on screen over a value that never took it.
 */
@Composable
internal fun rememberFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = rememberTextFieldState(value)
    val latestValue = rememberUpdatedState(value)
    val latestChange = rememberUpdatedState(onValueChange)

    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { typed ->
            if (typed == latestValue.value) return@collect
            latestChange.value(typed)
            withFrameNanos { }
            if (latestValue.value != typed) state.setTextAndPlaceCursorAtEnd(latestValue.value)
        }
    }
    return state
}
