package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import hivens.config.Protocol
import hivens.core.api.AuthException
import hivens.core.api.TwoFactorRequiredException
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.AuthStatus
import hivens.auth.AuthProvider
import hivens.auth.OfflineAuthProvider
import hivens.core.data.SessionData
import hivens.auth.AccountStore
import hivens.launcher.network.CertificateTrustGate
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.components.ConfirmCodeDialog
import hivens.ui.components.MicrosoftSignInButton
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxCalloutBanner
import hivens.ui.nx.NxCalloutTone
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.platform.SystemActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

@Composable
fun LoginPanel(
    onLogin: (SessionData) -> Unit,
    showOffline: Boolean = true,
    showMicrosoft: Boolean = true,
) {
    val authService: AuthProvider              = koinInject()
    val credentialsManager: AccountStore       = koinInject()
    val protocolConfig: ServerProtocolConfig   = koinInject()
    val certificateGate: CertificateTrustGate  = koinInject()
    val offlineProvider: OfflineAuthProvider   = koinInject()
    val settingsService: ISettingsService      = koinInject()
    val s            = LocalStrings.current
    val scope        = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var login        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    // Seeded from the persisted choice rather than always-on: the box gates every
    // save path below, so a session-local default of true silently re-armed saving
    // on the next start for a user who had turned it off.
    var rememberMe   by remember { mutableStateOf(settingsService.getSettings().saveCredentials) }
    var isLoading    by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // The choice outlives the panel, so it is persisted on the flip rather than at
    // login: a user who unticks and then closes the window without signing in has
    // still expressed it. Does NOT touch credentials already stored -- turning the
    // box off stops future saves and nothing else.
    val setRememberMe: (Boolean) -> Unit = { value ->
        rememberMe = value
        settingsService.updateSettings { it.copy(saveCredentials = value) }
    }

    // 2FA flow state. Which path a TWOAUTH demand takes is decided by the
    // provider's AuthCapabilities.supports2FA: a capable provider opens the
    // [twoFactorPending] / completeTwoFactor / ConfirmCodeDialog path, which is
    // now what SmartyCraft takes. The [twoFactorUnsupported] banner remains for a
    // provider that raises the demand without being able to answer it.
    data class TwoFactorPending(val uid: String, val username: String, val password: String, val serverId: String)
    var twoFactorPending      by remember { mutableStateOf<TwoFactorPending?>(null) }
    var twoFactorError        by remember { mutableStateOf<String?>(null) }
    var twoFactorBusy         by remember { mutableStateOf(false) }
    var twoFactorUnsupported  by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor        = NxInk.main,
        unfocusedTextColor      = NxInk.main,
        focusedBorderColor      = NxColor.lead(),
        unfocusedBorderColor    = NxInk.quiet.copy(alpha = 0.22f),
        focusedLabelColor       = NxColor.lead(),
        unfocusedLabelColor     = NxInk.quiet,
        cursorColor             = NxColor.lead(),
        focusedContainerColor   = Color.Transparent,
        unfocusedContainerColor = Color.Transparent
    )

    fun doLogin() {
        if (login.isBlank() || password.isBlank()) { errorMessage = s.loginErrorEmpty; return }
        focusManager.clearFocus()
        isLoading             = true
        errorMessage          = null
        twoFactorUnsupported  = false
        // No account name in any of these: the ring pre-fills a crash report and is
        // copied into the diagnostic bundle, and what it is for is what happened.
        hivens.core.diag.ActionRing.record("Login attempt")
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val sess = authService.login(login, password, Protocol.DEFAULT_SERVER_ID)
                    if (rememberMe) credentialsManager.saveAccount(sess, authService.id)
                    sess
                }
                hivens.core.diag.ActionRing.record("Login OK")
                // Cleared on success as well. Where the panel stays on screen after a
                // sign-in, a profile section signing in a second provider, it went on
                // showing the spinner over a form that was done.
                isLoading = false
                onLogin(session)
            } catch (e: TwoFactorRequiredException) {
                isLoading = false
                if (authService.capabilities.supports2FA) {
                    // Provider runs a real second factor: open the code dialog.
                    hivens.core.diag.ActionRing.record("Login: 2FA required, prompting for code")
                    twoFactorPending = TwoFactorPending(
                        uid = e.uid.orEmpty(),
                        username = login,
                        password = password,
                        serverId = Protocol.DEFAULT_SERVER_ID,
                    )
                } else {
                    // The provider raised a second-factor demand it cannot
                    // complete. Nothing to prompt for, so say so plainly.
                    hivens.core.diag.ActionRing.record(
                        "Login: 2FA detected, rejected (unsupported on this provider)"
                    )
                    twoFactorUnsupported = true
                }
            } catch (e: AuthException) {
                isLoading = false
                hivens.core.diag.ActionRing.record(
                    "Login failed (auth): ssl=${e.isSslError} msg=${e.message?.take(80)}"
                )
                when {
                    // The certificate question is the shell's to ask now, so the form
                    // no longer draws its own copy of it: the same refusal reaches the
                    // roster and the news, and one dialog for one decision beats a
                    // banner that only the login path could raise. The retry rides
                    // along -- accepting here means the user wanted to sign in. The
                    // gate records the grant before it runs, so the same provider
                    // now reaches the host over the bypassed channel.
                    e.isSslError -> certificateGate.request(protocolConfig.sslBypassHost) {
                        doLogin()
                    }
                    else         -> errorMessage = e.message
                        ?.replace("java.lang.Exception: ", "")
                        ?.substringAfter("API: ")
                        ?: s.loginErrorGeneric
                }
            } catch (e: Exception) {
                isLoading    = false
                hivens.core.diag.ActionRing.record("Login failed (generic): msg=${e.message?.take(80)}")
                errorMessage = e.message ?: s.loginErrorGeneric
            }
        }
    }

    fun playOffline() {
        val name = login.trim()
        if (name.isEmpty()) { errorMessage = s.loginErrorEmpty; return }
        focusManager.clearFocus()
        errorMessage = null
        scope.launch {
            // Caught like the sign-in above. Unguarded, a settings file that could not
            // be written threw out of the composition's scope and raised the crash
            // dialog over a press of "play offline".
            val session = try {
                withContext(Dispatchers.IO) {
                    val sess = offlineProvider.login(name, "", "")
                    // Remember the offline name so a restart -- or the Settings offline
                    // toggle -- restores this identity without re-typing.
                    settingsService.updateSettings { it.copy(offlinePlayerName = name) }
                    sess
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hivens.core.diag.ActionRing.record("Play offline failed: msg=${e.message?.take(80)}")
                errorMessage = e.message ?: s.loginErrorGeneric
                return@launch
            }
            hivens.core.diag.ActionRing.record("Play offline")
            onLogin(session)
        }
    }

    fun submitTwoFactor(code: String) {
        val pending = twoFactorPending ?: return
        twoFactorBusy = true
        twoFactorError = null
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val sess = authService.completeTwoFactor(
                        username = pending.username, password = pending.password,
                        serverId = pending.serverId, uid = pending.uid, code = code,
                    )
                    if (rememberMe) credentialsManager.saveAccount(sess, authService.id)
                    sess
                }
                hivens.core.diag.ActionRing.record("Login OK after 2FA")
                twoFactorBusy = false
                twoFactorPending = null
                onLogin(session)
            } catch (e: AuthException) {
                twoFactorBusy = false
                hivens.core.diag.ActionRing.record(
                    "2FA verify failed: status=${e.status}"
                )
                when (e.status) {
                    AuthStatus.WRONG_CODE -> twoFactorError = s.auth2faInvalid
                    AuthStatus.TWO_FACTOR_EXPIRED -> {
                        // Session is gone server-side -- close the dialog and
                        // surface in the main login form so the user retries
                        // from scratch.
                        twoFactorPending = null
                        errorMessage = s.auth2faExpired
                    }
                    else -> twoFactorError = e.message ?: s.loginErrorGeneric
                }
            } catch (e: Exception) {
                twoFactorBusy = false
                twoFactorError = e.message ?: s.loginErrorGeneric
            }
        }
    }

    // 2FA prompt -- renders only while we're awaiting a code. Decoupled
    // from the login form below so the form retains its state (username,
    // password, rememberMe) for the resume path. Dismissal cancels the
    // 2FA flow without clearing the form, letting the user retry.
    twoFactorPending?.let {
        // Puppet: this dialog overrides the screen marker while open so
        // /screen returns "Login_2FA" -- drivers can detect the modal and
        // pivot to the 2FA-specific element ids instead of the form ones.
        PuppetScreen("Login_2FA")
        ConfirmCodeDialog(
            onDismiss = {
                twoFactorPending = null
                twoFactorError = null
                twoFactorBusy = false
            },
            onSubmit = { code -> submitTwoFactor(code) },
            errorMessage = twoFactorError,
            isSubmitting = twoFactorBusy,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PuppetScreen("Login")
        Text(
            text       = s.loginTitle,
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color      = NxInk.main
        )

        // ── 2FA unsupported banner ────────────────────────────────────────
        if (twoFactorUnsupported) {
            NxCalloutBanner(
                tone  = NxCalloutTone.Warning,
                title = s.auth2faUnsupportedTitle,
                body  = s.auth2faUnsupportedBody,
            ) {
                OutlinedButton(
                    onClick  = { twoFactorUnsupported = false },
                    modifier = Modifier.align(Alignment.End),
                    shape    = MaterialTheme.shapes.small,
                ) {
                    Text(s.auth2faUnsupportedDismiss, color = NxInk.quiet)
                }
            }
        }

        // ── Regular error ─────────────────────────────────────────────────
        if (errorMessage != null) {
            Text(
                text     = errorMessage ?: "",
                style    = MaterialTheme.typography.bodySmall,
                color    = NxColor.status(Status.Error),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NxColor.status(Status.Error).copy(alpha = 0.08f),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(8.dp)
            )
        }

        // ── Fields ────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = login,
            onValueChange = { login = it; errorMessage = null; twoFactorUnsupported = false },
            label         = { Text(s.loginUsername) },
            modifier      = Modifier.fillMaxWidth(),
            singleLine    = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            shape          = MaterialTheme.shapes.small,
            colors         = fieldColors
        )
        PuppetField("login.username", login) {
            login = it
            errorMessage = null
        }

        OutlinedTextField(
            value                = password,
            onValueChange        = { password = it; errorMessage = null; twoFactorUnsupported = false },
            label                = { Text(s.loginPassword) },
            modifier             = Modifier.fillMaxWidth(),
            singleLine           = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions      = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction    = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { doLogin() }),
            shape   = MaterialTheme.shapes.small,
            colors  = fieldColors
        )
        PuppetField("login.password", password) {
            password = it
            errorMessage = null
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked         = rememberMe,
                onCheckedChange = setRememberMe,
                colors          = CheckboxDefaults.colors(
                    checkedColor   = NxColor.lead(),
                    uncheckedColor = NxInk.quiet.copy(alpha = 0.4f)
                )
            )
            Text(
                text  = s.loginRemember,
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet
            )
        }
        PuppetToggle("login.rememberMe", rememberMe, onValueChange = setRememberMe)

        // LOG IN -- chaos target (only when not loading, loading state stays reliable)
        if (isLoading) {
            Button(
                onClick   = {},
                enabled   = false,
                modifier  = Modifier.fillMaxWidth().height(42.dp),
                shape     = MaterialTheme.shapes.small,
                colors    = ButtonDefaults.buttonColors(
                    disabledContainerColor = NxColor.lead().copy(alpha = 0.5f)
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) {
                CircularProgressIndicator(
                    color       = Color.White,
                    modifier    = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }
        } else {
            Flexible("login_submit_btn", FlexibleKind.Button) {
                NxButton(
                    label     = s.loginButton,
                    onClick   = { doLogin() },
                    modifier  = Modifier.fillMaxWidth(),
                    style     = NxButtonStyle.Primary,
                    minHeight = 42.dp,
                )
            }
        }
        PuppetClick("login.submit", enabled = !isLoading) { doLogin() }

        // REGISTER -- chaos target
        Flexible("login_register_btn", FlexibleKind.Button) {
            NxButton(
                label     = s.loginRegister,
                onClick   = { SystemActions.openUrl("${protocolConfig.baseUrl}/register") },
                modifier  = Modifier.fillMaxWidth(),
                style     = NxButtonStyle.Tertiary,
                minHeight = 42.dp,
            )
        }
        PuppetClick("login.register") {
            SystemActions.openUrl("${protocolConfig.baseUrl}/register")
        }

        // PLAY OFFLINE -- offline identity, no network. Reuses the username field
        // as the offline name and remembers it for next time.
        if (showOffline) {
            Flexible("login_offline_btn", FlexibleKind.Button) {
                NxButton(
                    label     = s.loginPlayOffline,
                    onClick   = { playOffline() },
                    modifier  = Modifier.fillMaxWidth(),
                    style     = NxButtonStyle.Tertiary,
                    minHeight = 42.dp,
                )
            }
            PuppetClick("login.playOffline") { playOffline() }
        }

        // SIGN IN WITH MICROSOFT -- present only when a client id is configured
        // (the provider registers and advertises device-code capability then).
        if (showMicrosoft) {
            MicrosoftSignInButton(onSignedIn = onLogin, rememberAccount = rememberMe)
        }
    }
}

// ─── Account Panel ────────────────────────────────────────────────────────────

