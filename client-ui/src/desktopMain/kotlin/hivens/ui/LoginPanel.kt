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
import hivens.launcher.network.NetworkState
import hivens.launcher.ProfileManager
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
import hivens.ui.theme.NxTheme
import hivens.ui.platform.SystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

@Composable
fun LoginPanel(
    onLogin: (SessionData) -> Unit,
    showOffline: Boolean = true,
    showMicrosoft: Boolean = true,
) {
    val authService: AuthProvider              = koinInject()
    val insecureAuthService: AuthProvider      = koinInject(named("insecure"))
    val credentialsManager: AccountStore = koinInject()
    val profileManager: ProfileManager         = koinInject()
    val protocolConfig: ServerProtocolConfig   = koinInject()
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
    var sslWarning   by remember { mutableStateOf(false) }

    // The choice outlives the panel, so it is persisted on the flip rather than at
    // login: a user who unticks and then closes the window without signing in has
    // still expressed it. Does NOT touch credentials already stored -- turning the
    // box off stops future saves and nothing else.
    val setRememberMe: (Boolean) -> Unit = { value ->
        rememberMe = value
        settingsService.saveSettings(settingsService.getSettings().copy(saveCredentials = value))
    }

    // 2FA flow state. Which path a TWOAUTH demand takes is decided by the
    // provider's AuthCapabilities.supports2FA: a capable provider opens the
    // [twoFactorPending] / completeTwoFactor / ConfirmCodeDialog path; the
    // SmartyCraft provider sets supports2FA = false, so the demand surfaces the
    // [twoFactorUnsupported] banner instead (its 2FA login succeeds on the wire
    // but breaks every game-side authenticated call after).
    //
    // [service] is the provider that raised the demand. The SSL-bypass retry
    // logs in through insecureAuthService, whose pendingTwoFactor cache is a
    // different instance from the secure provider's -- completing the code
    // against the wrong one would miss the cached login and re-dial the very
    // TLS channel the user just bypassed.
    data class TwoFactorPending(val uid: String, val username: String, val password: String, val serverId: String, val service: AuthProvider)
    var twoFactorPending      by remember { mutableStateOf<TwoFactorPending?>(null) }
    var twoFactorError        by remember { mutableStateOf<String?>(null) }
    var twoFactorBusy         by remember { mutableStateOf(false) }
    var twoFactorUnsupported  by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor        = NxTheme.colors.textPrimary,
        unfocusedTextColor      = NxTheme.colors.textPrimary,
        focusedBorderColor      = NxTheme.colors.primary,
        unfocusedBorderColor    = NxTheme.colors.textSecondary.copy(alpha = 0.22f),
        focusedLabelColor       = NxTheme.colors.primary,
        unfocusedLabelColor     = NxTheme.colors.textSecondary,
        cursorColor             = NxTheme.colors.primary,
        focusedContainerColor   = Color.Transparent,
        unfocusedContainerColor = Color.Transparent
    )

    fun doLogin(service: AuthProvider = authService) {
        if (login.isBlank() || password.isBlank()) { errorMessage = s.loginErrorEmpty; return }
        focusManager.clearFocus()
        isLoading             = true
        sslWarning            = false
        errorMessage          = null
        twoFactorUnsupported  = false
        hivens.core.diag.ActionRing.record("Login attempt: user=$login")
        scope.launch {
            try {
                val session = withContext(Dispatchers.IO) {
                    val lastServer = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                    val sess = service.login(login, password, lastServer)
                    if (rememberMe) credentialsManager.save(sess)
                    sess
                }
                hivens.core.diag.ActionRing.record("Login OK: user=$login")
                onLogin(session)
            } catch (e: TwoFactorRequiredException) {
                isLoading = false
                if (service.capabilities.supports2FA) {
                    // Provider runs a real second factor: open the code dialog.
                    hivens.core.diag.ActionRing.record("Login: 2FA required, prompting for code")
                    val lastServer = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                    twoFactorPending = TwoFactorPending(
                        uid = e.uid.orEmpty(),
                        username = login,
                        password = password,
                        serverId = lastServer,
                        service = service,
                    )
                } else {
                    // The wire-side completeTwoFactor() works for the login
                    // itself, but the issued accessToken breaks every game-side
                    // authenticated call after that. Rather than ship a
                    // half-functional flow, the banner explains and asks the
                    // user to disable 2FA on the site.
                    hivens.core.diag.ActionRing.record(
                        "Login: 2FA detected, rejected (unsupported on this provider)"
                    )
                    twoFactorUnsupported = true
                }
            } catch (e: AuthException) {
                isLoading = false
                hivens.core.diag.ActionRing.record(
                    "Login failed (auth): user=$login ssl=${e.isSslError} msg=${e.message?.take(80)}"
                )
                when {
                    e.isSslError -> sslWarning = true
                    else         -> errorMessage = e.message
                        ?.replace("java.lang.Exception: ", "")
                        ?.substringAfter("API: ")
                        ?: s.loginErrorGeneric
                }
            } catch (e: Exception) {
                isLoading    = false
                hivens.core.diag.ActionRing.record("Login failed (generic): user=$login msg=${e.message?.take(80)}")
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
            val session = withContext(Dispatchers.IO) {
                val sess = offlineProvider.login(name, "", "")
                // Remember the offline name so a restart -- or the Settings offline
                // toggle -- restores this identity without re-typing.
                settingsService.saveSettings(settingsService.getSettings().copy(offlinePlayerName = name))
                sess
            }
            hivens.core.diag.ActionRing.record("Play offline: name=$name")
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
                    val sess = pending.service.completeTwoFactor(
                        username = pending.username, password = pending.password,
                        serverId = pending.serverId, uid = pending.uid, code = code,
                    )
                    if (rememberMe) credentialsManager.save(sess)
                    sess
                }
                hivens.core.diag.ActionRing.record("Login OK after 2FA: user=${pending.username}")
                twoFactorBusy = false
                twoFactorPending = null
                onLogin(session)
            } catch (e: AuthException) {
                twoFactorBusy = false
                hivens.core.diag.ActionRing.record(
                    "2FA verify failed: user=${pending.username} status=${e.status}"
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
            color      = NxTheme.colors.textPrimary
        )

        // ── SSL warning banner ────────────────────────────────────────────
        if (sslWarning) {
            NxCalloutBanner(
                tone  = NxCalloutTone.Warning,
                title = s.sslWarningTitle,
                body  = s.sslWarningBody,
            ) {
                // Trust-duration prompt + 3 grant buttons. Each click both
                // grants the bypass for that duration AND retries login --
                // single-click UX. Cancel button is on its own row above so
                // the dangerous actions don't accidentally read as the same
                // affordance as the safe one.
                OutlinedButton(
                    onClick  = { sslWarning = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = MaterialTheme.shapes.small
                ) {
                    Text(s.sslWarningCancel, color = NxTheme.colors.textSecondary)
                }
                Text(
                    text  = s.sslWarningTrustPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary,
                )
                val acceptColors = ButtonDefaults.buttonColors(containerColor = NxTheme.colors.warnAccent)
                fun acceptFor(unit: java.time.temporal.ChronoUnit, amount: Long, label: String) {
                    // 100-year future for "always" -- long enough that no user
                    // will outlive it, short enough to not overflow ISO-8601
                    // formatting that a far-future Instant.MAX would.
                    val until = java.time.Instant.now().plus(amount, unit)
                    hivens.core.diag.ActionRing.record(
                        "SSL bypass accepted by user (login retry) -- granted: $label",
                    )
                    NetworkState.grantBypass(protocolConfig.sslBypassHost, until)
                    doLogin(insecureAuthService)
                }
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.HOURS, 1, "1 hour") },
                        modifier = Modifier.weight(1f),
                        shape    = MaterialTheme.shapes.small,
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrustHour, color = Color.Black) }
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.DAYS, 30, "30 days") },
                        modifier = Modifier.weight(1f),
                        shape    = MaterialTheme.shapes.small,
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrust30Days, color = Color.Black) }
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.DAYS, 36500, "always (100y)") },
                        modifier = Modifier.weight(1f),
                        shape    = MaterialTheme.shapes.small,
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrustAlways, color = Color.Black) }
                }
            }
        }

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
                    Text(s.auth2faUnsupportedDismiss, color = NxTheme.colors.textSecondary)
                }
            }
        }

        // ── Regular error ─────────────────────────────────────────────────
        if (errorMessage != null) {
            Text(
                text     = errorMessage ?: "",
                style    = MaterialTheme.typography.bodySmall,
                color    = NxTheme.colors.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NxTheme.colors.error.copy(alpha = 0.08f),
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(8.dp)
            )
        }

        // ── Fields ────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = login,
            onValueChange = { login = it; errorMessage = null; sslWarning = false; twoFactorUnsupported = false },
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
            sslWarning = false
        }

        OutlinedTextField(
            value                = password,
            onValueChange        = { password = it; errorMessage = null; sslWarning = false; twoFactorUnsupported = false },
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
            sslWarning = false
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked         = rememberMe,
                onCheckedChange = setRememberMe,
                colors          = CheckboxDefaults.colors(
                    checkedColor   = NxTheme.colors.primary,
                    uncheckedColor = NxTheme.colors.textSecondary.copy(alpha = 0.4f)
                )
            )
            Text(
                text  = s.loginRemember,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary
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
                    disabledContainerColor = NxTheme.colors.primary.copy(alpha = 0.5f)
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

