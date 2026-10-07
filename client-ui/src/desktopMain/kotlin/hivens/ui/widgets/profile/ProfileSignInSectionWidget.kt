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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.auth.AuthProviderRegistry
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackAuthRequirement
import hivens.core.data.releasingFace
import hivens.core.data.SessionData
import hivens.auth.AccountStore
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
import hivens.ui.utils.rememberReadOffMain
import hivens.widget.model.Widget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

private const val MS_KEY = PackAuthRequirement.Microsoft.PROVIDER_KEY

// Microsoft profile section (slot "signin"). Signed into Microsoft it shows the
// licensed identity -- Minecraft name, UUID, the live skin -- with a sign-out.
// Signed out it offers the device-code sign-in when a client id is configured,
// or explains that this build has none. Resolves the Microsoft account directly,
// independent of the shell face (which may be a SmartyCraft account).
//
// Skin and cape management (upload, cape selection via the Mojang API) is the
// next, deeper pass -- this section is the identity + auth foundation.
@Widget(id = "profile.signin", displayName = "widget.profile.signin", removable = false)
@Composable
fun ProfileSignInSectionWidget() {
    val ctx = LocalProfileContext.current
    val credentials: AccountStore = koinInject()
    val authRegistry: AuthProviderRegistry = koinInject()
    val settingsService: ISettingsService = koinInject()
    val scope = rememberCoroutineScope()

    // The device-code provider is registered only when a client id is configured.
    val msaConfigured = remember { authRegistry.hasDeviceCodeProvider() }
    // Shared with the account section and the nav's face picker -- see
    // ProfileContext.accountsRevision.
    val revision = ctx.accountsRevision
    // Nothing until the store has answered, as in the SmartyCraft section.
    val msSession = (rememberReadOffMain(revision.value, ctx.session) { credentials.accountFor(MS_KEY) } ?: return).value

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
                MicrosoftAccount(msSession) { revision.value++ }
            } else {
                MicrosoftSignInButton(
                    onSignedIn = {
                        scope.launch {
                            withContext(Dispatchers.IO) { credentials.faceSession(settingsService) }
                                ?.let { ctx.onLogin(it) }
                            revision.value++
                        }
                    },
                    puppetId = "account.signin.microsoft",
                )
            }
        }
    }
}

@Composable
private fun MicrosoftAccount(session: SessionData, onChanged: () -> Unit) {
    val ctx = LocalProfileContext.current
    val credentials: AccountStore = koinInject()
    val settingsService: ISettingsService = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var signingOut by remember { mutableStateOf(false) }

    // Signing out of Microsoft removes its account; if it was the only one, that
    // is a full logout -- route it through the confirm so a dismissed dialog
    // leaves the account intact (see the SmartyCraft section for the same shape).
    // One at a time, for the same reason as there.
    fun signOut() {
        if (signingOut) return
        signingOut = true
        scope.launch {
            try {
                val accounts = withContext(Dispatchers.IO) { credentials.listAccounts() }
                if (accounts.size <= 1) {
                    ctx.onLogout()
                    return@launch
                }
                val face = withContext(Dispatchers.IO) {
                    accounts.firstOrNull { it.providerId == MS_KEY }
                        ?.let { credentials.removeAccount(it.providerId, it.accountId) }
                    // The face choice goes with the account it named -- see releasingFace.
                    settingsService.updateSettings { it.releasingFace(MS_KEY) }
                    credentials.faceSession(settingsService)
                }
                face?.let { ctx.onLogin(it) } ?: ctx.onLogout()
                onChanged()
            } finally {
                signingOut = false
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = session.playerName,
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
