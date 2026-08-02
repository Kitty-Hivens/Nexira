package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.core.api.TwoFactorRequiredException
import hivens.core.data.PackAuthRequirement
import hivens.core.diag.ActionRing
import hivens.ui.notifications.TwoFactorLaunchGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Answers a launch that is waiting on a second factor.
 *
 * MUST be composed inside [hivens.ui.theme.NxTheme]: the prompt is a `Dialog`, which
 * on desktop gets its own composition, and a dialog raised from outside the theme
 * finds no `LocalNxColors` and takes the shell down with it.
 *
 * The flow is one round trip. The gate hands over the target's server; a login
 * against it provokes the demand and yields the `uid` the code must be signed
 * against; the code unlocks the session that came WITH that demand; the session is
 * saved and returned to the waiting relaunch. An account that has since dropped its
 * second factor logs in cleanly and goes straight through, with the stored flag
 * cleared so background sync stops treating it as gated.
 */
@Composable
fun TwoFactorPromptHost() {
    val gate: TwoFactorLaunchGate = koinInject()
    val accounts: AccountStore = koinInject()
    val auth: AuthProvider = koinInject()

    val pending by gate.pending.collectAsState()
    var uid by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pending) {
        uid = null
        error = null
        val request = pending ?: return@LaunchedEffect
        val stored = withContext(Dispatchers.IO) {
            accounts.accountFor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
        }
        val pass = stored?.cachedPassword
        if (stored == null || pass.isNullOrEmpty()) {
            // Nothing to sign in with; a prompt that could never succeed is worse
            // than saying so and letting the launch fail visibly.
            ActionRing.record("2FA launch of ${request.label}: no stored credentials to sign in with")
            gate.cancel()
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { auth.login(stored.playerName, pass, request.serverId) } }
            .onSuccess { fresh ->
                withContext(Dispatchers.IO) {
                    accounts.saveAccount(
                        fresh.copy(twoFactor = false),
                        PackAuthRequirement.SmartyCraft.PROVIDER_KEY,
                    )
                }
                gate.resume(fresh.copy(mintedNow = true))
            }
            .onFailure { failure ->
                if (failure is TwoFactorRequiredException) {
                    uid = failure.uid.orEmpty()
                } else {
                    ActionRing.record("2FA launch of ${request.label} could not start: ${failure.message?.take(60)}")
                    gate.cancel()
                }
            }
    }

    val request = pending
    if (request == null || uid == null) return

    ConfirmCodeDialog(
        onDismiss = { gate.cancel() },
        onSubmit = { code ->
            busy = true
            error = null
            scope.launch {
                val stored = withContext(Dispatchers.IO) {
                    accounts.accountFor(PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
                }
                runCatching {
                    val session = withContext(Dispatchers.IO) {
                        auth.completeTwoFactor(
                            username = stored?.playerName.orEmpty(),
                            password = stored?.cachedPassword.orEmpty(),
                            serverId = request.serverId,
                            uid = uid.orEmpty(),
                            code = code,
                        )
                    }
                    // Inside the same runCatching: a vault write can fail, and letting
                    // that escape leaves the dialog open with no error and the gate stuck.
                    withContext(Dispatchers.IO) {
                        accounts.saveAccount(session, PackAuthRequirement.SmartyCraft.PROVIDER_KEY)
                    }
                    session
                }.onSuccess { session ->
                    busy = false
                    gate.resume(session)
                }.onFailure { failure ->
                    busy = false
                    error = failure.message
                }
            }
        },
        errorMessage = error,
        isSubmitting = busy,
    )
}
