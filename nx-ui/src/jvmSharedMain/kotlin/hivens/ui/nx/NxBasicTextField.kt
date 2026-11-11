package hivens.ui.nx

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle

/**
 * A text field for the screens that draw their own frame around it: [value] in, [onValueChange]
 * out, and [decorationBox] to lay the field inside whatever it sits in.
 *
 * What the string based `BasicTextField` was, on the text state the framework keeps now. The state
 * follows the caller's string both ways (see [rememberFieldState]), so a caller that rewrites or
 * refuses an edit sees the field show what it kept. [onKeyboardAction] is what the IME action runs.
 */
@Composable
fun NxBasicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: TextStyle = TextStyle.Default,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onKeyboardAction: KeyboardActionHandler? = null,
    singleLine: Boolean = false,
    cursorBrush: Brush = SolidColor(Color.Black),
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { inner -> inner() },
) {
    val state = rememberFieldState(value, onValueChange)
    BasicTextField(
        state            = state,
        modifier         = modifier,
        enabled          = enabled,
        textStyle        = textStyle,
        keyboardOptions  = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits       = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        cursorBrush      = cursorBrush,
        decorator        = TextFieldDecorator { inner -> decorationBox(inner) },
    )
}
