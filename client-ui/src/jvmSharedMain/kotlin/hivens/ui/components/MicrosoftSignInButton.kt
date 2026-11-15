package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.DeviceCodeSignIn
import hivens.accounts.api.ProviderView
import hivens.accounts.api.SignInError
import hivens.accounts.api.SignInFlow
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.Status
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull

/**
 * Microsoft device-code sign-in as a self-contained control: the button, and the
 * [DeviceCodeDialog] the sign-in drives. Renders nothing unless the accounts module
 * lists a device-code provider, which it does only when a Microsoft client id is
 * configured: resolved by what the provider does, never by its id.
 *
 * The sign-in itself is the accounts module's: the request, the wait, keeping the
 * account when [rememberAccount] asks for it, and signing the launcher in once it
 * works. This fires it and draws where it stands.
 *
 * The device-code request can fail before the dialog ever opens (a bad client id,
 * an Azure app that doesn't allow personal accounts, no network), so that error
 * surfaces inline under the button, and the dialog only carries errors from the
 * wait that follows.
 */
@Composable
fun MicrosoftSignInButton(
    modifier: Modifier = Modifier,
    rememberAccount: Boolean = true,
    label: String? = null,
    puppetId: String = "login.microsoft",
) {
    val providers = rememberScopedSource(AccountKeys.Providers).valueOrNull() ?: return
    if (providers.none { it.kind == ProviderView.DEVICE_CODE }) return
    val flows = rememberScopedSource(AccountKeys.SignIns).valueOrNull() ?: return
    val signIn = rememberScopedCommand(AccountKeys.SignInMicrosoft) ?: return
    val cancel = rememberScopedCommand(AccountKeys.CancelSignIn) ?: return
    val s = LocalStrings.current
    val flow = flows[AccountKeys.MICROSOFT]

    // A press while one is under way does nothing: the module holds one at a time.
    fun start() = signIn(DeviceCodeSignIn(remember = rememberAccount))

    (flow as? SignInFlow.DeviceCode)?.let { shown ->
        DeviceCodeDialog(
            userCode = shown.userCode,
            verificationUri = shown.verificationUri,
            onOpenBrowser = { SystemActions.openUrl(shown.verificationUri) },
            onCancel = { cancel(AccountKeys.MICROSOFT) },
            errorMessage = shown.error?.let { it.message ?: s.loginErrorGeneric },
        )
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Flexible("login_microsoft_btn", FlexibleKind.Button) {
            NxButton(
                label = label ?: s.loginMicrosoft,
                onClick = { start() },
                modifier = Modifier.fillMaxWidth(),
                style = NxButtonStyle.Tertiary,
                minHeight = 42.dp,
            )
        }
        when (flow) {
            SignInFlow.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = NxColor.lead(),
                )
                Spacer(Modifier.width(8.dp))
                Text(s.msaWaiting, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
            }
            // Errors from the request itself (before any dialog) show here. The
            // dialog owns errors from the wait once it is open.
            is SignInFlow.Failed -> Text(
                text = flow.error.words(s),
                style = MaterialTheme.typography.bodySmall,
                color = NxColor.status(Status.Error),
            )
            else -> Unit
        }
    }
    PuppetClick(puppetId) { start() }
}

/** What a failed sign-in step says, in the person's words where the provider gave none. */
internal fun SignInError.words(s: AppStrings): String = when (code) {
    SignInError.EMPTY -> s.loginErrorEmpty
    SignInError.WRONG_CODE -> s.auth2faInvalid
    SignInError.SECOND_FACTOR_EXPIRED -> s.auth2faExpired
    else -> message ?: s.loginErrorGeneric
}
