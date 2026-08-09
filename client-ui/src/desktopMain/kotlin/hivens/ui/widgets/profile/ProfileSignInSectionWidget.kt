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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import hivens.ui.customization.glassSurfaceAlpha
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
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalMonoFamily
import hivens.ui.theme.LocalStyle
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import org.koin.compose.koinInject

private val MS_KEY = PackAuthRequirement.Microsoft.PROVIDER_KEY

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
fun ProfileSignInSectionWidget(instance: WidgetInstance) {
    val ctx = LocalProfileContext.current
    val credentials: AccountStore = koinInject()
    val authRegistry: AuthProviderRegistry = koinInject()
    val settingsService: ISettingsService = koinInject()

    // The device-code provider is registered only when a client id is configured.
    val msaConfigured = remember { authRegistry.hasDeviceCodeProvider() }
    var refreshKey by remember { mutableIntStateOf(0) }
    val msSession = remember(refreshKey, ctx.session) { credentials.accountFor(MS_KEY) }

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
                MicrosoftAccount(msSession) { refreshKey++ }
            } else {
                MicrosoftSignInButton(
                    onSignedIn = {
                        credentials.faceSession(settingsService)?.let { ctx.onLogin(it) }
                        refreshKey++
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

    // Signing out of Microsoft removes its account; if it was the only one, that
    // is a full logout -- route it through the confirm so a dismissed dialog
    // leaves the account intact (see the SmartyCraft section for the same shape).
    fun signOut() {
        if (credentials.listAccounts().size <= 1) {
            ctx.onLogout()
            return
        }
        credentials.listAccounts().firstOrNull { it.providerId == MS_KEY }
            ?.let { credentials.removeAccount(it.accountId) }
        // The face choice goes with the account it named -- see releasingFace.
        settingsService.saveSettings(settingsService.getSettings().releasingFace(MS_KEY))
        credentials.faceSession(settingsService)?.let { ctx.onLogin(it) } ?: ctx.onLogout()
        onChanged()
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = session.playerName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = NxTheme.colors.textPrimary,
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
    val style = LocalStyle.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(NxTheme.colors.background.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("UUID", style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = dashedUuid(uuid),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = LocalMonoFamily.current,
                color = NxTheme.colors.textPrimary,
            )
        }
        IconButton(onClick = { SystemActions.copyToClipboard(uuid) }) {
            Symbol(NxIcon.ContentCopy, "UUID", tint = NxTheme.colors.textSecondary)
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
