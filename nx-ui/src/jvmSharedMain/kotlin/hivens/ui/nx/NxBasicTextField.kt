package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

/**
 * A text field for the screens that draw their own frame around it: [value] in, [onValueChange]
 * out, and [decorationBox] to lay the field inside whatever it sits in.
 *
 * What the string based `BasicTextField` was, on the text state the framework keeps now. The state
 * follows the caller's string both ways (see [rememberFieldState]), so a caller that rewrites or
 * refuses an edit sees the field show what it kept. [onKeyboardAction] is what the IME action runs.
 * [inputTransformation] filters what the person types before the caller is given it, so a refused
 * character never shows in the field.
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
    inputTransformation: InputTransformation? = null,
    decorationBox: @Composable (innerTextField: @Composable () -> Unit) -> Unit = { inner -> inner() },
) {
    val field = rememberFieldState(value, onValueChange, inputTransformation)
    BasicTextField(
        state               = field.text,
        modifier            = modifier,
        enabled             = enabled,
        inputTransformation = field.input,
        textStyle           = textStyle,
        keyboardOptions     = keyboardOptions,
        onKeyboardAction    = onKeyboardAction,
        lineLimits          = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
        cursorBrush         = cursorBrush,
        decorator           = TextFieldDecorator { inner -> decorationBox(inner) },
    )
}

@Preview
@Composable
private fun NxBasicTextFieldDarkPreview() = BasicTextFields(dark = true)

@Preview
@Composable
private fun NxBasicTextFieldLightPreview() = BasicTextFields(dark = false)

@Composable
private fun BasicTextFields(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(320.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for ((value, enabled) in listOf("Typed text" to true, "" to true, "Read only" to false)) {
                val style = MaterialTheme.typography.bodyMedium.copy(color = if (enabled) NxInk.main else NxInk.off)
                NxBasicTextField(
                    value         = value,
                    onValueChange = {},
                    enabled       = enabled,
                    singleLine    = true,
                    textStyle     = style,
                    cursorBrush   = SolidColor(NxColor.lead()),
                    modifier      = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box(Modifier.border(1.dp, NxInk.line, MaterialTheme.shapes.small).padding(10.dp)) {
                            if (value.isEmpty()) Text("Own frame, empty", style = style.copy(color = NxInk.quiet))
                            inner()
                        }
                    },
                )
            }
        }
    }
}
