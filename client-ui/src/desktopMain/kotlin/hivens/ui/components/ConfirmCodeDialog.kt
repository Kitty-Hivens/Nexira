package hivens.ui.components

import hivens.ui.nx.NxButton
import hivens.ui.theme.LocalMonoFamily
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.theme.NxTheme

/**
 * Six-digit TOTP prompt for the second factor of the SmartyCraft login.
 *
 * Pure presentation: filters input to digits only, caps at 6, surfaces an
 * inline error string supplied by the caller, disables the submit button
 * until the field is exactly 6 digits long. The actual `completeTwoFactor`
 * call lives in the LoginPanel -- that layer keeps the originating
 * username/password/serverId in scope and decides whether to keep the
 * dialog open (CODE -- re-prompt) or dismiss + restart (TWO_FACTOR_EXPIRED).
 *
 * @param errorMessage non-null when the previous submit failed; rendered
 *        below the field so the user sees what went wrong without losing
 *        the input typed so far.
 * @param isSubmitting disables the field + submit button while a verify
 *        round-trip is in flight.
 */
@Composable
fun ConfirmCodeDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    errorMessage: String? = null,
    isSubmitting: Boolean = false,
) {
    val s = LocalStrings.current
    var code by remember { mutableStateOf("") }

    // The code is time-boxed, so put the caret in the field the moment the
    // dialog opens -- otherwise the user has to click it first, friction on a
    // focus-finicky Wayland stack. runCatching guards the node-not-yet-attached
    // race the way the rest of the UI does.
    val codeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { codeFocus.requestFocus() } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(420.dp),
            shape = MaterialTheme.shapes.large,
            color = NxTheme.colors.surface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
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

                PuppetField("login.twoFactor.code", code, enabled = !isSubmitting) { raw ->
                    code = raw.filter { it.isDigit() }.take(6)
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { raw ->
                        // Cap at 6, strip non-digits -- the server rejects anything
                        // else, and we'd rather not send it. Pasting a code with a
                        // surrounding "code: 123456" prefix becomes "123456".
                        code = raw.filter { it.isDigit() }.take(6)
                    },
                    placeholder = {
                        Text(
                            s.auth2faPlaceholder,
                            color = NxTheme.colors.textSecondary.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    singleLine = true,
                    enabled = !isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (code.length == 6 && !isSubmitting) onSubmit(code) },
                    ),
                    textStyle = TextStyle(
                        fontFamily = LocalMonoFamily.current,
                        fontSize = 24.sp,
                        textAlign = TextAlign.Center,
                        letterSpacing = 8.sp,
                        color = NxTheme.colors.textPrimary,
                    ),
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth().focusRequester(codeFocus),
                )

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
                            modifier = Modifier.size(18.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp,
                            color = NxTheme.colors.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                        Text(s.auth2faCancel, color = NxTheme.colors.textSecondary)
                    }
                    PuppetClick("login.twoFactor.cancel", enabled = !isSubmitting) { onDismiss() }
                    Spacer(Modifier.width(8.dp))
                    NxButton(
                        label   = s.auth2faSubmit,
                        onClick = { onSubmit(code) },
                        enabled = code.length == 6 && !isSubmitting,
                    )
                    PuppetClick("login.twoFactor.submit", enabled = code.length == 6 && !isSubmitting) {
                        onSubmit(code)
                    }
                }
            }
        }
    }
}
