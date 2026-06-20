package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.auth.AuthProviderRegistry
import hivens.core.data.PackAuthRequirement
import hivens.launcher.CredentialsManager
import hivens.launcher.CredentialsManager.StoredAccount
import hivens.ui.LoginPanel
import hivens.ui.components.MicrosoftSignInButton
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

// Sign-in / Security slot of the profile surface. Signed out it renders the
// login form (reused verbatim, with its SSL-bypass + 2FA + remember-me flows);
// signing in completes in place -- onLogin flips the app to Authenticated and
// the surface re-renders on the Account tab.
//
// Signed in it is the account roster: one section per credential provider
// (SmartyCraft, Microsoft) listing the signed-in accounts, with per-account
// remove and an add affordance in an empty section. The accounts coexist
// (multi-active) -- a launch routes to the one matching the content's provider.
// The displayed "face" follows licence priority (the Microsoft account first);
// add/remove recompute it via primarySession and push it to the shell. Removing
// the last account signs out. Logout itself lives in the left-rail nav.
//
// Both states are capped to a comfortable column width so the form/roster read
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
                AccountRoster(ctx)
            }
        }
    }
}

@Composable
private fun AccountRoster(ctx: ProfileContext) {
    val s = LocalStrings.current
    val af = LocalAprilFools.current
    val credentials: CredentialsManager = koinInject()
    val authRegistry: AuthProviderRegistry = koinInject()
    // A Microsoft section makes sense only when the provider is configured (it
    // advertises device-code capability then) or an account is already stored.
    val msaConfigured = remember { authRegistry.all.any { it.capabilities.supportsDeviceCode } }

    var refreshKey by remember { mutableIntStateOf(0) }
    val accounts = remember(refreshKey) { credentials.listAccounts() }

    // After an add or remove, re-resolve the licence-priority face and push it to
    // the shell (null -> signed out), then force the roster to re-read.
    fun syncFace() {
        val face = credentials.primarySession()
        if (face != null) ctx.onLogin(face) else ctx.onLogout()
        refreshKey++
    }

    val currentAccountId = ctx.session?.let { it.uuid.ifBlank { it.playerName } }
    val scKey = PackAuthRequirement.SmartyCraft.PROVIDER_KEY
    val msKey = PackAuthRequirement.Microsoft.PROVIDER_KEY

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = s.accountsTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = CelestiaTheme.colors.textPrimary,
        )

        ProviderSection(
            title = "SmartyCraft",
            accounts = accounts.filter { it.providerId == scKey },
            currentAccountId = currentAccountId,
            onRemove = { credentials.removeAccount(it); syncFace() },
        ) {
            // SmartyCraft uses the username/password form; reveal it inline, with
            // the offline + Microsoft alternatives suppressed in this context.
            var adding by remember { mutableStateOf(false) }
            if (adding) {
                LoginPanel(
                    onLogin = { adding = false; syncFace() },
                    showOffline = false,
                    showMicrosoft = false,
                )
            } else {
                af.ChaosButton(
                    id = "account_add_smartycraft_btn",
                    text = s.loginButton,
                    onClick = { adding = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = glassSurfaceAlpha(0.5f),
                        contentColor = CelestiaTheme.colors.textPrimary,
                    ),
                )
                PuppetClick("account.add.smartycraft") { adding = true }
            }
        }

        if (msaConfigured || accounts.any { it.providerId == msKey }) {
            ProviderSection(
                title = "Microsoft",
                accounts = accounts.filter { it.providerId == msKey },
                currentAccountId = currentAccountId,
                onRemove = { credentials.removeAccount(it); syncFace() },
            ) {
                // Device-code button; renders nothing if no client id is configured.
                MicrosoftSignInButton(
                    onSignedIn = { syncFace() },
                    puppetId = "account.add.microsoft",
                )
            }
        }

        // Bulk forget: drop every account and sign out, the multi-active analogue
        // of the per-account remove above.
        af.ChaosButton(
            id = "profile_forget_credentials_btn",
            text = s.profileForgetSavedSignIn,
            onClick = { credentials.clear(); ctx.onLogout() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = glassSurfaceAlpha(0.5f),
                contentColor = CelestiaTheme.colors.textPrimary,
            ),
        )
        PuppetClick("profile.forgetCredentials") { credentials.clear(); ctx.onLogout() }
    }
}

@Composable
private fun ProviderSection(
    title: String,
    accounts: List<StoredAccount>,
    currentAccountId: String?,
    onRemove: (String) -> Unit,
    emptyContent: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Section header is the provider's own name -- a proper noun, not localized.
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = CelestiaTheme.colors.textSecondary,
        )
        if (accounts.isEmpty()) {
            emptyContent()
        } else {
            accounts.forEach { account ->
                AccountRow(
                    account = account,
                    isCurrent = account.accountId == currentAccountId,
                    onRemove = { onRemove(account.accountId) },
                )
            }
        }
    }
}

@Composable
private fun AccountRow(account: StoredAccount, isCurrent: Boolean, onRemove: () -> Unit) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    val accent = if (isCurrent) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(NxIcon.AccountCircle, null, tint = accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = account.displayName.ifBlank { account.username },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isCurrent) CelestiaTheme.colors.textPrimary else CelestiaTheme.colors.textPrimary.copy(alpha = 0.85f),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove) {
            Symbol(NxIcon.Delete, s.accountRemove, tint = CelestiaTheme.colors.textSecondary, modifier = Modifier.size(20.dp))
        }
        PuppetClick("account.remove.${account.accountId}") { onRemove() }
    }
}
