package hivens.ui.widgets.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.launcher.CredentialsManager
import hivens.ui.LoginPanel
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

// Sign-in / Security slot of the profile surface. Signed out it renders the
// login form (reused verbatim, with its SSL-bypass + 2FA + remember-me flows);
// signing in completes in place -- onLogin flips the app to Authenticated and
// the surface re-renders on the Account tab. Signed in it manages the saved
// credential: forget it so the next launch does not auto-login (the only UI
// for that today). Logout lives on the Account tab.
// removable = false: a user must not be able to editor-delete their own sign-in.
@Widget(id = "profile.signin", displayName = "widget.profile.signin", removable = false)
@Composable
fun ProfileSignInSectionWidget(instance: WidgetInstance) {
    val ctx = LocalProfileContext.current
    if (ctx.session == null) {
        LoginPanel(onLogin = ctx.onLogin)
        return
    }

    val s = LocalStrings.current
    val credentials: CredentialsManager = koinInject()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text  = s.profileSecurityHint,
            style = MaterialTheme.typography.bodyMedium,
            color = CelestiaTheme.colors.textSecondary,
        )
        OutlinedButton(onClick = { credentials.clear() }, modifier = Modifier.fillMaxWidth()) {
            Text(s.profileForgetSavedSignIn)
        }
        PuppetClick("profile.forgetCredentials") { credentials.clear() }
    }
}
