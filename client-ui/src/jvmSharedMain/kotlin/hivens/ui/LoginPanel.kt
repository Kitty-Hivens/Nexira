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
import androidx.compose.foundation.text.input.KeyboardActionHandler
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.PasswordSignIn
import hivens.accounts.api.SignInCode
import hivens.accounts.api.SignInError
import hivens.accounts.api.SignInFlow
import hivens.ui.nx.rememberFieldState
import androidx.compose.ui.unit.dp
import hivens.ui.components.ConfirmCodeDialog
import hivens.ui.components.MicrosoftSignInButton
import hivens.ui.components.words
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
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

/**
 * The SmartyCraft sign-in form, with offline play and, where [showMicrosoft], the
 * Microsoft button under it.
 *
 * Everything it does goes through the accounts module's keys: the sign-in, the second
 * factor the provider may ask for, offline play and the remember choice. It keeps the
 * fields and nothing else, and draws where the module says the sign-in stands. Once a
 * sign-in works the module signs the launcher in, so the form has nothing to hand on.
 * Where no accounts module is brought in it draws nothing.
 */
@Composable
fun LoginPanel(
    showOffline: Boolean = true,
    showMicrosoft: Boolean = true,
) {
    val flows = rememberScopedSource(AccountKeys.SignIns).valueOrNull() ?: return
    val providers = rememberScopedSource(AccountKeys.Providers).valueOrNull() ?: return
    val rememberMe = rememberScopedSource(AccountKeys.Remember).valueOrNull() ?: return
    val signIn = rememberScopedCommand(AccountKeys.SignInSmartyCraft) ?: return
    val signInOffline = rememberScopedCommand(AccountKeys.SignInOffline) ?: return
    val answerCode = rememberScopedCommand(AccountKeys.SignInCode) ?: return
    val cancel = rememberScopedCommand(AccountKeys.CancelSignIn) ?: return
    val setRemember = rememberScopedCommand(AccountKeys.SetRemember) ?: return
    val s            = LocalStrings.current
    val focusManager = LocalFocusManager.current

    var login        by remember { mutableStateOf("") }
    var password     by remember { mutableStateOf("") }

    val provider = AccountKeys.SMARTYCRAFT
    val flow = flows[provider]
    val offlineFlow = flows[AccountKeys.OFFLINE]
    val registerUrl = providers.firstOrNull { it.key == provider }?.registerUrl
    val isLoading = flow == SignInFlow.Working
    val twoFactorUnsupported = (flow as? SignInFlow.Failed)?.error?.code == SignInError.SECOND_FACTOR_UNSUPPORTED
    // What went wrong, the sign-in's own failure first and then offline play's.
    val errorMessage = listOfNotNull(flow, offlineFlow)
        .filterIsInstance<SignInFlow.Failed>()
        .firstOrNull { it.error.code != SignInError.SECOND_FACTOR_UNSUPPORTED }
        ?.error?.words(s)

    // Typing over a failure says it was read: the form stops showing it.
    fun edited() {
        if (flow is SignInFlow.Failed) cancel(provider)
        if (offlineFlow is SignInFlow.Failed) cancel(AccountKeys.OFFLINE)
    }

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
        // The button and the automation hook are both off while one is in flight, and
        // Enter in the password field was not. The module holds one sign-in at a time
        // as well, since on SmartyCraft each login retires the uid the one before it got.
        if (isLoading) return
        if (login.isNotBlank() && password.isNotBlank()) focusManager.clearFocus()
        signIn(PasswordSignIn(login, password, remember = rememberMe))
    }

    fun playOffline() {
        if (login.isNotBlank()) focusManager.clearFocus()
        signInOffline(login)
    }

    // The 2FA prompt renders only while the provider waits for a code. Decoupled
    // from the login form below so the form keeps its fields for the resume path.
    // Dismissal gives the sign-in up without clearing the form, letting the user retry.
    (flow as? SignInFlow.SecondFactor)?.let { asked ->
        // Puppet: this dialog overrides the screen marker while open so
        // /screen returns "Login_2FA" -- drivers can detect the modal and
        // pivot to the 2FA-specific element ids instead of the form ones.
        PuppetScreen("Login_2FA")
        ConfirmCodeDialog(
            onDismiss = { cancel(provider) },
            onSubmit = { code -> answerCode(SignInCode(provider, code)) },
            errorMessage = asked.error?.words(s),
            isSubmitting = asked.checking,
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
                    onClick  = { cancel(provider) },
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
                text     = errorMessage,
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
            onValueChange = { login = it; edited() },
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
            edited()
        }

        val passwordState = rememberFieldState(password) { password = it; edited() }
        OutlinedSecureTextField(
            state            = passwordState,
            label            = { Text(s.loginPassword) },
            modifier         = Modifier.fillMaxWidth(),
            keyboardOptions  = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction    = ImeAction.Done
            ),
            onKeyboardAction = KeyboardActionHandler { doLogin() },
            shape            = MaterialTheme.shapes.small,
            colors           = fieldColors
        )
        PuppetField("login.password", password) {
            password = it
            edited()
        }

        // The choice outlives the panel, so it is kept on the flip rather than at a
        // sign-in, and turning it off stops future saves and nothing else.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked         = rememberMe,
                onCheckedChange = setRemember,
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
        PuppetToggle("login.rememberMe", rememberMe, onValueChange = setRemember)

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
        if (registerUrl != null) {
            Flexible("login_register_btn", FlexibleKind.Button) {
                NxButton(
                    label     = s.loginRegister,
                    onClick   = { SystemActions.openUrl(registerUrl) },
                    modifier  = Modifier.fillMaxWidth(),
                    style     = NxButtonStyle.Tertiary,
                    minHeight = 42.dp,
                )
            }
            PuppetClick("login.register") {
                SystemActions.openUrl(registerUrl)
            }
        }

        // PLAY OFFLINE -- offline identity, no network. Reuses the username field
        // as the offline name, which the module keeps for next time.
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
            MicrosoftSignInButton(rememberAccount = rememberMe)
        }
    }
}
