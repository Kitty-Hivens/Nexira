package hivens.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.auth.AuthProviderRegistry
import hivens.auth.DeviceCodeAuthProvider
import hivens.auth.DeviceCodeChallenge
import hivens.core.data.SessionData
import hivens.core.diag.ActionRing
import hivens.launcher.CredentialsManager
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("MicrosoftSignInButton")

/**
 * Microsoft device-code sign-in as a self-contained control: the button plus the
 * poll loop and the [DeviceCodeDialog] it drives. Renders nothing unless a
 * Microsoft client id is configured (the provider registers and advertises
 * device-code capability only then) -- resolved by capability, never by id.
 *
 * On success the account is saved (when [rememberAccount]) and [onSignedIn] fires
 * with the fresh [SessionData]. Shared by the login panel (first sign-in) and the
 * account roster (adding a Microsoft account while already signed in elsewhere).
 *
 * The device-code request can fail before the dialog ever opens (a bad client id,
 * an Azure app that doesn't allow personal accounts, no network), so that error
 * surfaces inline under the button -- the dialog only carries errors from the
 * poll that follows.
 */
@Composable
fun MicrosoftSignInButton(
    onSignedIn: (SessionData) -> Unit,
    modifier: Modifier = Modifier,
    rememberAccount: Boolean = true,
    label: String? = null,
    puppetId: String = "login.microsoft",
) {
    val authRegistry: AuthProviderRegistry     = koinInject()
    val credentialsManager: CredentialsManager = koinInject()
    val s   = LocalStrings.current
    val scope = rememberCoroutineScope()

    val provider = remember { authRegistry.all.firstOrNull { it.capabilities.supportsDeviceCode } }
    val deviceCodeProvider = provider as? DeviceCodeAuthProvider ?: return
    val providerId = provider.id

    var deviceCodePending by remember { mutableStateOf<DeviceCodeChallenge?>(null) }
    var deviceCodeError   by remember { mutableStateOf<String?>(null) }
    var requesting        by remember { mutableStateOf(false) }
    var msaJob            by remember { mutableStateOf<Job?>(null) }

    fun start() {
        if (requesting || deviceCodePending != null) return
        deviceCodeError = null
        requesting = true
        msaJob = scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { deviceCodeProvider.requestDeviceCode() }
                requesting = false
                deviceCodePending = challenge
                val session = withContext(Dispatchers.IO) { deviceCodeProvider.awaitToken(challenge) }
                if (rememberAccount) credentialsManager.saveAccount(session, providerId)
                ActionRing.record("Microsoft sign-in OK: ${session.playerName}")
                deviceCodePending = null
                onSignedIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Microsoft sign-in failed", e)
                ActionRing.record("Microsoft sign-in failed: ${e.message?.take(120)}")
                deviceCodeError = e.message ?: s.loginErrorGeneric
            } finally {
                requesting = false
            }
        }
    }

    deviceCodePending?.let { challenge ->
        DeviceCodeDialog(
            userCode = challenge.userCode,
            verificationUri = challenge.verificationUri,
            onOpenBrowser = { SystemActions.openUrl(challenge.verificationUri) },
            onCancel = { msaJob?.cancel(); deviceCodePending = null; deviceCodeError = null },
            errorMessage = deviceCodeError,
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
        when {
            requesting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = NxTheme.colors.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(s.msaWaiting, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            }
            // Errors from the request itself (before any dialog) show here; the
            // dialog owns errors from the poll once it is open.
            deviceCodeError != null && deviceCodePending == null -> Text(
                text = deviceCodeError ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.error,
            )
        }
    }
    PuppetClick(puppetId) { start() }
}
