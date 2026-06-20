package hivens.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.auth.AuthProviderRegistry
import hivens.auth.DeviceCodeAuthProvider
import hivens.auth.DeviceCodeChallenge
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Microsoft device-code sign-in as a self-contained control: the button plus the
 * poll loop and the [DeviceCodeDialog] it drives. Renders nothing unless a
 * Microsoft client id is configured (the provider registers and advertises
 * device-code capability only then) -- resolved by capability, never by id.
 *
 * On success the account is saved (when [rememberAccount]) and [onSignedIn] fires
 * with the fresh [SessionData]. Shared by the login panel (first sign-in) and the
 * account roster (adding a Microsoft account while already signed in elsewhere).
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
    val af  = LocalAprilFools.current
    val scope = rememberCoroutineScope()

    val provider = remember { authRegistry.all.firstOrNull { it.capabilities.supportsDeviceCode } }
    val deviceCodeProvider = provider as? DeviceCodeAuthProvider ?: return
    val providerId = provider.id

    var deviceCodePending by remember { mutableStateOf<DeviceCodeChallenge?>(null) }
    var deviceCodeError   by remember { mutableStateOf<String?>(null) }
    var msaJob            by remember { mutableStateOf<Job?>(null) }

    fun start() {
        deviceCodeError = null
        msaJob = scope.launch {
            try {
                val challenge = withContext(Dispatchers.IO) { deviceCodeProvider.requestDeviceCode() }
                deviceCodePending = challenge
                val session = withContext(Dispatchers.IO) { deviceCodeProvider.awaitToken(challenge) }
                if (rememberAccount) credentialsManager.saveAccount(session, providerId)
                hivens.core.diag.ActionRing.record("Microsoft sign-in OK: ${session.playerName}")
                deviceCodePending = null
                onSignedIn(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hivens.core.diag.ActionRing.record("Microsoft sign-in failed: ${e.message?.take(80)}")
                deviceCodeError = e.message ?: s.loginErrorGeneric
            }
        }
    }

    deviceCodePending?.let { challenge ->
        DeviceCodeDialog(
            userCode = challenge.userCode,
            verificationUri = challenge.verificationUri,
            onOpenBrowser = { SystemActions.openUrl(challenge.verificationUri) },
            onCopyCode = { SystemActions.copyToClipboard(challenge.userCode) },
            onCancel = { msaJob?.cancel(); deviceCodePending = null; deviceCodeError = null },
            errorMessage = deviceCodeError,
        )
    }

    af.ChaosButton(
        id      = "login_microsoft_btn",
        text    = label ?: s.loginMicrosoft,
        onClick = { start() },
        modifier = modifier.fillMaxWidth().height(42.dp),
        colors   = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor   = CelestiaTheme.colors.primary,
        ),
    )
    PuppetClick(puppetId) { start() }
}
