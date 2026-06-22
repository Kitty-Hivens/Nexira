package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalMonoFamily

/**
 * Microsoft device-code prompt: shows the verification URL + the user code to
 * enter on another device (selectable, with an open-browser shortcut), and a
 * spinner while the launcher polls for confirmation. Pure presentation -- the poll loop
 * and the resulting [hivens.core.data.SessionData] live in the LoginPanel, which
 * cancels the poll when this dialog is dismissed.
 *
 * The code + URL are mirrored to read-only puppet fields so an automation driver
 * can read them out of `/elements`.
 */
@Composable
fun DeviceCodeDialog(
    userCode: String,
    verificationUri: String,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
    errorMessage: String? = null,
) {
    val s = LocalStrings.current
    PuppetScreen("Login_DeviceCode")

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(420.dp),
            shape = MaterialTheme.shapes.large,
            color = NxTheme.colors.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    s.msaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = NxTheme.colors.textPrimary,
                )
                Text(
                    s.msaInstruction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NxTheme.colors.textSecondary,
                )
                Text(
                    verificationUri,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NxTheme.colors.primary,
                )
                PuppetField("login.msa.verificationUrl", verificationUri, enabled = false) {}

                // Selectable so the code can be highlighted + copied by hand, not
                // only via the copy button.
                SelectionContainer {
                    Text(
                        userCode,
                        fontFamily = LocalMonoFamily.current,
                        fontSize = 26.sp,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center,
                        color = NxTheme.colors.textPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                PuppetField("login.msa.userCode", userCode, enabled = false) {}

                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = NxTheme.colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = NxTheme.colors.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            s.msaWaiting,
                            style = MaterialTheme.typography.bodySmall,
                            color = NxTheme.colors.textSecondary,
                        )
                    }
                }

                // No copy button -- the code above is selectable, and a dedicated
                // copy went unnoticed anyway. Cancel + open fit one row comfortably.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(s.auth2faCancel, color = NxTheme.colors.textSecondary)
                    }
                    PuppetClick("login.msa.cancel") { onCancel() }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onOpenBrowser,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = NxTheme.colors.primary),
                    ) {
                        Text(s.msaOpenBrowser, maxLines = 1)
                    }
                    PuppetClick("login.msa.openBrowser") { onOpenBrowser() }
                }
            }
        }
    }
}
