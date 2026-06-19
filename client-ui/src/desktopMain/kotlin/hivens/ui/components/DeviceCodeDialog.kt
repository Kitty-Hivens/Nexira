package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.OutlinedButton
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
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalMonoFamily

/**
 * Microsoft device-code prompt: shows the verification URL + the user code to
 * enter on another device, with copy / open-browser shortcuts, and a spinner
 * while the launcher polls for confirmation. Pure presentation -- the poll loop
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
    onCopyCode: () -> Unit,
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
            color = CelestiaTheme.colors.surface,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    s.msaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = CelestiaTheme.colors.textPrimary,
                )
                Text(
                    s.msaInstruction,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CelestiaTheme.colors.textSecondary,
                )
                Text(
                    verificationUri,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CelestiaTheme.colors.primary,
                )
                PuppetField("login.msa.verificationUrl", verificationUri, enabled = false) {}

                Text(
                    userCode,
                    fontFamily = LocalMonoFamily.current,
                    fontSize = 26.sp,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center,
                    color = CelestiaTheme.colors.textPrimary,
                    modifier = Modifier.fillMaxWidth(),
                )
                PuppetField("login.msa.userCode", userCode, enabled = false) {}

                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = CelestiaTheme.colors.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CelestiaTheme.colors.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            s.msaWaiting,
                            style = MaterialTheme.typography.bodySmall,
                            color = CelestiaTheme.colors.textSecondary,
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(s.auth2faCancel, color = CelestiaTheme.colors.textSecondary)
                    }
                    PuppetClick("login.msa.cancel") { onCancel() }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onCopyCode, shape = MaterialTheme.shapes.small) {
                        Text(s.msaCopyCode, color = CelestiaTheme.colors.textPrimary)
                    }
                    PuppetClick("login.msa.copyCode") { onCopyCode() }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = onOpenBrowser,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = CelestiaTheme.colors.primary),
                    ) {
                        Text(s.msaOpenBrowser)
                    }
                    PuppetClick("login.msa.openBrowser") { onOpenBrowser() }
                }
            }
        }
    }
}
