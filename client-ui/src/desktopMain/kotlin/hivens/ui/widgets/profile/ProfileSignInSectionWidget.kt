package hivens.ui.widgets.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.launcher.CredentialsManager
import hivens.ui.LoginPanel
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.easter.LocalAprilFools
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
// for that today). Logout lives in the left-rail nav.
//
// Both states are capped to a comfortable column width so the form/buttons read
// as controls in a card, not full-pane bars across the wide profile content.
// removable = false: a user must not be able to editor-delete their own sign-in.
@Widget(id = "profile.signin", displayName = "widget.profile.signin", removable = false)
@Composable
fun ProfileSignInSectionWidget(instance: WidgetInstance) {
    val ctx = LocalProfileContext.current
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.widthIn(max = 440.dp)) {
            if (ctx.session == null) {
                LoginPanel(onLogin = ctx.onLogin)
            } else {
                val s = LocalStrings.current
                val af = LocalAprilFools.current
                val credentials: CredentialsManager = koinInject()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = s.profileSecurityHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = CelestiaTheme.colors.textSecondary,
                    )
                    // ChaosButton (same as top-up): glass look, the style engine's
                    // corner, and the NoOpIndication that suppresses M3's mismatched
                    // rectangular hover layer that a plain Button shows here.
                    af.ChaosButton(
                        id = "profile_forget_credentials_btn",
                        text = s.profileForgetSavedSignIn,
                        onClick = { credentials.clear() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = glassSurfaceAlpha(0.5f),
                            contentColor = CelestiaTheme.colors.textPrimary,
                        ),
                    )
                    PuppetClick("profile.forgetCredentials") { credentials.clear() }
                }
            }
        }
    }
}
