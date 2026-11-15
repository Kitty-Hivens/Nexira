package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.AccountView
import hivens.ui.components.MicrosoftSignInButton
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.theme.LocalMonoFamily
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

private const val MS_KEY = AccountKeys.MICROSOFT

// Microsoft profile section (slot "signin"). Signed into Microsoft it shows the
// licensed identity -- Minecraft name, UUID, the live skin -- with a sign-out.
// Signed out it offers the device-code sign-in when a client id is configured,
// or explains that this build has none. Resolves the Microsoft account directly,
// independent of the shell face (which may be a SmartyCraft account).
//
// Skin and cape management (upload, cape selection via the Mojang API) is the
// next, deeper pass -- this section is the identity + auth foundation.
@Widget(
    id = "profile.signin",
    displayName = "widget.profile.signin",
    removable = false,
    needs = ["accounts.list", "accounts.providers", "accounts.signIns", "auth.signIn.microsoft", "auth.signIn.cancel", "accounts.remove", "accounts.removals", "auth.signOut"],
)
@Composable
fun ProfileSignInSectionWidget() {
    // The device-code provider is listed only when a client id is configured.
    val providers = rememberScopedSource(AccountKeys.Providers).valueOrNull() ?: return
    val msaConfigured = providers.hasDeviceCodeProvider()
    // Nothing until the store has answered, as in the SmartyCraft section. Shared with
    // the account section and the nav's face picker.
    val accounts = rememberScopedSource(AccountKeys.Accounts).valueOrNull() ?: return
    val msSession = accounts.firstOrNull { it.providerKey == MS_KEY }

    // Microsoft / multi-account is deferred to a later release. With no Microsoft
    // client id configured the provider never registers, so there is nothing to
    // sign into here -- render the section as nothing rather than a "not configured
    // in this build" dead-end. It returns in full the moment a build ships a client
    // id (which is the off-by-default switch: no release ships one).
    if (msSession == null && !msaConfigured) return

    PuppetScreen("Profile_Microsoft")
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.widthIn(max = 520.dp).padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (msSession != null) {
                MicrosoftAccount(msSession, othersLeft = accounts.size > 1)
            } else {
                // A sign-in that works signs the launcher in, as the face the accounts give.
                MicrosoftSignInButton(puppetId = "account.signin.microsoft")
            }
        }
    }
}

@Composable
private fun MicrosoftAccount(session: AccountView, othersLeft: Boolean) {
    // Signing out of Microsoft removes its account. If it was the only one, or no face
    // is left after it, that is a full logout, which asks first through the window's
    // sign-out question (see the SmartyCraft section for the same shape).
    val signOut = rememberAccountSignOut(MS_KEY, othersLeft) ?: return
    val s = LocalStrings.current

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = session.name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = NxInk.main,
        )
        UuidCard(session.uuid)
        // The live skin + cape manager (Mojang-sourced, not the SmartyCraft skin
        // service this section's identity comes from) is the next pass.
        Flexible("profile_ms_signout_btn", FlexibleKind.Button) {
            NxButton(
                label = s.profileSignOutMicrosoft,
                onClick = { signOut() },
                modifier = Modifier.widthIn(min = 200.dp),
                style = NxButtonStyle.Secondary,
            )
        }
        PuppetClick("account.signout.microsoft") { signOut() }
    }
}

@Composable
private fun UuidCard(uuid: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(NxColor.page.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("UUID", style = MaterialTheme.typography.labelSmall, color = NxInk.quiet)
            Spacer(Modifier.height(2.dp))
            Text(
                text = dashedUuid(uuid),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = LocalMonoFamily.current,
                color = NxInk.main,
            )
        }
        IconButton(onClick = { SystemActions.copyToClipboard(uuid) }) {
            Symbol(NxIcon.ContentCopy, "UUID", tint = NxInk.quiet)
        }
        PuppetClick("account.microsoft.copyUuid") { SystemActions.copyToClipboard(uuid) }
    }
}

// Dashes a 32-char hex UUID into 8-4-4-4-12; passes anything else through.
private fun dashedUuid(uuid: String): String =
    if (uuid.length == 32 && uuid.all { it.isLetterOrDigit() }) {
        "${uuid.substring(0, 8)}-${uuid.substring(8, 12)}-${uuid.substring(12, 16)}-" +
            "${uuid.substring(16, 20)}-${uuid.substring(20)}"
    } else {
        uuid
    }
