package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/** How many digits the second factor asks for. */
private const val CODE_LENGTH = 6

/**
 * Six-digit TOTP prompt for the second factor of the SmartyCraft login.
 *
 * Pure presentation: filters input to digits only, caps at [CODE_LENGTH], surfaces
 * an inline error string supplied by the caller, disables the submit button until
 * the field is exactly [CODE_LENGTH] digits long. The actual `completeTwoFactor`
 * call lives in the LoginPanel -- that layer keeps the originating
 * username/password/serverId in scope and decides whether to keep the dialog open
 * (CODE -- re-prompt) or dismiss + restart (TWO_FACTOR_EXPIRED).
 *
 * The digits render as one cell per character over a single hidden field, rather
 * than as six focusable inputs. A code arrives from an authenticator app and is
 * usually pasted or typed in one run, so six inputs would buy nothing and cost the
 * focus-hopping every such form gets wrong -- and pasting `code: 123456` into cell
 * one would land the whole string in a one-character box.
 *
 * @param errorMessage non-null when the previous submit failed; rendered below the
 *        cells so the user sees what went wrong without losing what they typed.
 * @param isSubmitting disables input and submit while a verify round-trip is in
 *        flight.
 * @param puppetPrefix namespace for the control-surface ids. The same dialog answers
 *        two different demands -- the login form's and a launch's -- and a driver that
 *        cannot tell them apart answers the wrong one and calls the run green.
 */
@Composable
fun ConfirmCodeDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    errorMessage: String? = null,
    isSubmitting: Boolean = false,
    puppetPrefix: String = "login.twoFactor",
) {
    val s = LocalStrings.current
    var code by remember { mutableStateOf("") }

    // The code is time-boxed, so put the caret in the field the moment the dialog
    // opens -- otherwise the user has to click it first, friction on a focus-finicky
    // Wayland stack. runCatching guards the node-not-yet-attached race the way the
    // rest of the UI does.
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }

    fun accept(raw: String) {
        // Cap at CODE_LENGTH, strip non-digits -- the server rejects anything else
        // and we would rather not send it. Pasting "code: 123456" becomes "123456".
        code = raw.filter { it.isDigit() }.take(CODE_LENGTH)
    }

    val complete = code.length == CODE_LENGTH

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        NxSurface(level = NxSurfaceLevel.Floating, modifier = Modifier.width(420.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    s.auth2faTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = NxTheme.colors.textPrimary,
                )
                Text(
                    s.auth2faPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NxTheme.colors.textSecondary,
                )

                PuppetField("$puppetPrefix.code", code, enabled = !isSubmitting) { accept(it) }

                Box {
                    // The real input, invisible but focused: it owns the caret, the
                    // keyboard and paste, while the cells below are what is seen.
                    BasicTextField(
                        value = code,
                        onValueChange = { if (!isSubmitting) accept(it) },
                        singleLine = true,
                        enabled = !isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { if (complete && !isSubmitting) onSubmit(code) },
                        ),
                        cursorBrush = SolidColor(NxTheme.colors.primary),
                        textStyle = TextStyle(color = NxTheme.colors.textPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .alpha(0f)
                            .focusRequester(codeFocus),
                    )
                    CodeCells(code = code, hasError = errorMessage != null, dimmed = isSubmitting)
                }

                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = NxTheme.colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = NxTheme.colors.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    NxButton(
                        label   = s.auth2faCancel,
                        onClick = onDismiss,
                        style   = NxButtonStyle.Tertiary,
                        enabled = !isSubmitting,
                    )
                    PuppetClick("$puppetPrefix.cancel", enabled = !isSubmitting) { onDismiss() }
                    Spacer(Modifier.width(8.dp))
                    NxButton(
                        label   = s.auth2faSubmit,
                        onClick = { onSubmit(code) },
                        enabled = complete && !isSubmitting,
                    )
                    PuppetClick("$puppetPrefix.submit", enabled = complete && !isSubmitting) {
                        onSubmit(code)
                    }
                }
            }
        }
    }
}

/**
 * One cell per digit. The cell taking the next character carries the accent border,
 * so the caret's position is legible even though the real caret is on the hidden
 * field above.
 */
/**
 * Test seam for [CodeCells]: the cells are the part worth rendering on their own,
 * since a Dialog does not compose inside an offscreen scene.
 */
@Composable
internal fun ConfirmCodeCellsForTest(code: String, hasError: Boolean = false, dimmed: Boolean = false) =
    CodeCells(code = code, hasError = hasError, dimmed = dimmed)

@Composable
private fun CodeCells(code: String, hasError: Boolean, dimmed: Boolean) {
    val shape = RoundedCornerShape(LocalStyle.current.buttonCorner)
    val accent = if (hasError) NxTheme.colors.error else NxTheme.colors.primary
    Row(
        Modifier.fillMaxWidth().alpha(if (dimmed) 0.6f else 1f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(CODE_LENGTH) { index ->
            val filled = index < code.length
            val isNext = index == code.length
            NxSurface(
                level = NxSurfaceLevel.Sunken,
                glass = false,
                shape = shape,
                modifier = Modifier.weight(1f).height(56.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            color = if (isNext) accent.copy(alpha = 0.10f) else NxTheme.colors.surface.copy(alpha = 0f),
                            shape = shape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (filled) code[index].toString() else "",
                        style = TextStyle(
                            fontFamily = LocalMonoFamily.current,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center,
                            color = if (hasError) NxTheme.colors.error else NxTheme.colors.textPrimary,
                        ),
                    )
                }
            }
        }
    }
}
