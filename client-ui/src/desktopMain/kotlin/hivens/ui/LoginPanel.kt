package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import hivens.core.data.AuthStatus
import hivens.auth.AuthProvider
import hivens.core.data.SessionData
import hivens.launcher.CredentialsManager
import hivens.launcher.network.NetworkState
import hivens.launcher.ProfileManager
import hivens.launcher.network.ServerProtocolConfig
import hivens.ui.components.ConfirmCodeDialog
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetField
import hivens.ui.puppet.PuppetScreen
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.CelestiaTheme
import hivens.ui.platform.SystemActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.koin.core.qualifier.named

@Composable
fun LoginPanel(onLogin: (SessionData) -> Unit) {
    val authService: AuthProvider              = koinInject()
    val insecureAuthService: AuthProvider      = koinInject(named("insecure"))
    val credentialsManager: CredentialsManager = koinInject()
    val profileManager: ProfileManager         = koinInject()
    val protocolConfig: ServerProtocolConfig   = koinInject()
    val s            = LocalStrings.current
    val af           = LocalAprilFools.current
    val scope        = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var login        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }
    var rememberMe   by remember { mutableStateOf(true) }
    var isLoading    by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sslWarning   by remember { mutableStateOf(false) }

    // 2FA flow state. Which path a TWOAUTH demand takes is decided by the
    // provider's AuthCapabilities.supports2FA: a capable provider opens the
    // [twoFactorPending] / completeTwoFactor / ConfirmCodeDialog path; the
    // SmartyCraft provider sets supports2FA = false, so the demand surfaces the
    // [twoFactorUnsupported] banner instead (its 2FA login succeeds on the wire
    // but breaks every game-side authenticated call after).
    data class TwoFactorPending(val uid: String, val username: String, val password: String, val serverId: String)
    var twoFactorPending      by remember { mutableStateOf<TwoFactorPending?>(null) }
    var twoFactorError        by remember { mutableStateOf<String?>(null) }
    var twoFactorBusy         by remember { mutableStateOf(false) }
    var twoFactorUnsupported  by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor        = CelestiaTheme.colors.textPrimary,
        unfocusedTextColor      = CelestiaTheme.colors.textPrimary,
        focusedBorderColor      = CelestiaTheme.colors.primary,
        unfocusedBorderColor    = CelestiaTheme.colors.textSecondary.copy(alpha = 0.22f),
        focusedLabelColor       = CelestiaTheme.colors.primary,
        unfocusedLabelColor     = CelestiaTheme.colors.textSecondary,
        cursorColor             = CelestiaTheme.colors.primary,
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
                if (authService.capabilities.supports2FA) {
                    // Provider runs a real second factor: open the code dialog.
                    hivens.core.diag.ActionRing.record("Login: 2FA required, prompting for code")
                    val lastServer = profileManager.lastServerId ?: Protocol.DEFAULT_SERVER_ID
                    twoFactorPending = TwoFactorPending(
                        uid = e.uid.orEmpty(),
                        username = login,
                        password = password,
                        serverId = lastServer,
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
            color      = CelestiaTheme.colors.textPrimary
        )

        // ── SSL warning banner ────────────────────────────────────────────
        if (sslWarning) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFFF59E0B).copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text       = "⚠ ${s.sslWarningTitle}",
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFFF59E0B)
                )
                Text(
                    text  = s.sslWarningBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.85f)
                )
                // Trust-duration prompt + 3 grant buttons. Each click both
                // grants the bypass for that duration AND retries login --
                // single-click UX. Cancel button is on its own row above so
                // the dangerous actions don't accidentally read as the same
                // affordance as the safe one.
                OutlinedButton(
                    onClick  = { sslWarning = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(6.dp)
                ) {
                    Text(s.sslWarningCancel, color = CelestiaTheme.colors.textSecondary)
                }
                Text(
                    text  = s.sslWarningTrustPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
                val acceptColors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B))
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
                        shape    = RoundedCornerShape(6.dp),
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrustHour, color = Color.Black) }
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.DAYS, 30, "30 days") },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(6.dp),
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrust30Days, color = Color.Black) }
                    Button(
                        onClick  = { acceptFor(java.time.temporal.ChronoUnit.DAYS, 36500, "always (100y)") },
                        modifier = Modifier.weight(1f),
                        shape    = RoundedCornerShape(6.dp),
                        colors   = acceptColors,
                    ) { Text(s.sslWarningTrustAlways, color = Color.Black) }
                }
            }
        }

        // ── 2FA unsupported banner ────────────────────────────────────────
        if (twoFactorUnsupported) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = Color(0xFFF59E0B).copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text       = s.auth2faUnsupportedTitle,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFFF59E0B)
                )
                Text(
                    text  = s.auth2faUnsupportedBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textPrimary.copy(alpha = 0.85f)
                )
                OutlinedButton(
                    onClick  = { twoFactorUnsupported = false },
                    modifier = Modifier.align(Alignment.End),
                    shape    = RoundedCornerShape(6.dp),
                ) {
                    Text(s.auth2faUnsupportedDismiss, color = CelestiaTheme.colors.textSecondary)
                }
            }
        }

        // ── Regular error ─────────────────────────────────────────────────
        if (errorMessage != null) {
            Text(
                text     = errorMessage ?: "",
                style    = MaterialTheme.typography.bodySmall,
                color    = CelestiaTheme.colors.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = CelestiaTheme.colors.error.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(6.dp)
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
            shape          = RoundedCornerShape(8.dp),
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
            shape   = RoundedCornerShape(8.dp),
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
                onCheckedChange = { rememberMe = it },
                colors          = CheckboxDefaults.colors(
                    checkedColor   = CelestiaTheme.colors.primary,
                    uncheckedColor = CelestiaTheme.colors.textSecondary.copy(alpha = 0.4f)
                )
            )
            Text(
                text  = s.loginRemember,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary
            )
        }
        PuppetToggle("login.rememberMe", rememberMe) { rememberMe = it }

        // LOG IN -- chaos target (only when not loading, loading state stays reliable)
        if (isLoading) {
            Button(
                onClick   = {},
                enabled   = false,
                modifier  = Modifier.fillMaxWidth().height(42.dp),
                shape     = RoundedCornerShape(8.dp),
                colors    = ButtonDefaults.buttonColors(
                    disabledContainerColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
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
            af.ChaosButton(
                id       = "login_submit_btn",
                text     = s.loginButton,
                onClick  = { doLogin() },
                modifier = Modifier.fillMaxWidth().height(42.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = CelestiaTheme.colors.primary,
                ),
            )
        }
        PuppetClick("login.submit", enabled = !isLoading) { doLogin() }

        // REGISTER -- chaos target
        af.ChaosButton(
            id      = "login_register_btn",
            text    = s.loginRegister,
            onClick = {
                SystemActions.openUrl("${protocolConfig.baseUrl}/register")
            },
            modifier = Modifier.fillMaxWidth().height(42.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor   = CelestiaTheme.colors.primary,
            ),
        )
        PuppetClick("login.register") {
            SystemActions.openUrl("${protocolConfig.baseUrl}/register")
        }
    }
}

// ─── Account Panel ────────────────────────────────────────────────────────────

