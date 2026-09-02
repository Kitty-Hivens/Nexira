package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackAuthRequirement
import hivens.core.data.releasingFace
import hivens.core.data.SessionData
import hivens.auth.AccountStore
import hivens.ui.LoginPanel
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.skin3d.Cycles
import hivens.ui.skin3d.rememberSkinViewState
import hivens.ui.theme.NxTheme
import hivens.widget.model.Widget
import org.koin.compose.koinInject

private const val SC_KEY = PackAuthRequirement.SmartyCraft.PROVIDER_KEY

// SmartyCraft profile section (slot "account"). Signed into SmartyCraft it is the
// skin-forward account view -- name + status, balance + top-up, the 3D skin and
// its upload/refresh -- with a sign-out. Signed out of SmartyCraft (but present
// in the shell via another provider) it shows the SmartyCraft sign-in form.
// Resolves the SmartyCraft account directly rather than the shell face, since the
// face may belong to a different provider; Microsoft lives in its own section.
@Widget(id = "profile.account.section", displayName = "widget.profile.account.section")
@Composable
fun ProfileAccountSectionWidget() {
    val ctx = LocalProfileContext.current
    val credentials: AccountStore = koinInject()
    val settingsService: ISettingsService = koinInject()

    // The surface's revision rather than a key of this section's own: the nav's
    // face picker has to hear about a sign-out that happens here.
    val revision = ctx.accountsRevision
    val scSession = remember(revision.value, ctx.session) { credentials.accountFor(SC_KEY) }

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.widthIn(max = 520.dp)) {
            if (scSession == null) {
                // SmartyCraft uses the username/password form (plus offline); the
                // Microsoft button is suppressed -- it has its own section.
                LoginPanel(
                    onLogin = {
                        credentials.faceSession(settingsService)?.let { ctx.onLogin(it) }
                        revision.value++
                    },
                    showMicrosoft = false,
                )
            } else {
                SmartyCraftAccount(scSession) { revision.value++ }
            }
        }
    }
}

@Composable
private fun SmartyCraftAccount(session: SessionData, onChanged: () -> Unit) {
    val ctx = LocalProfileContext.current
    val credentials: AccountStore = koinInject()
    val settingsService: ISettingsService = koinInject()
    val s = LocalStrings.current

    // Bumped by the skin uploader so the skin re-loads after upload/refresh.
    var skinKey by remember { mutableIntStateOf(0) }
    val uploader = rememberSkinUploader(session) { skinKey++ }

    // Signing out of SmartyCraft removes its account; if it was the only one, that
    // is a full logout -- route it through the confirm (which clears + signs out)
    // so a dismissed dialog leaves the account intact.
    fun signOut() {
        if (credentials.listAccounts().size <= 1) {
            ctx.onLogout()
            return
        }
        credentials.listAccounts().firstOrNull { it.providerId == SC_KEY }
            ?.let { credentials.removeAccount(it.accountId) }
        // The face choice goes with the account it named -- see releasingFace.
        settingsService.saveSettings(settingsService.getSettings().releasingFace(SC_KEY))
        credentials.faceSession(settingsService)?.let { ctx.onLogin(it) } ?: ctx.onLogout()
        onChanged()
    }

    Column(Modifier.fillMaxSize().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AccountPanel(session)

        // Skin below the panel, with upload + refresh directly under it. The
        // hero idles (breath + head drift) instead of the turntable spin --
        // the character inhabits the page; drag still orbits for inspection.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkinHero(
                session.playerName,
                skinKey,
                Modifier.width(190.dp).height(300.dp),
                interactive = true,
                autoSpin = false,
                state = rememberSkinViewState(initialAnimation = Cycles.idle()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Flexible("profile_upload_skin_btn", FlexibleKind.Button) {
                    NxButton(
                        label = s.profileUploadSkin,
                        onClick = uploader.pick,
                        style = NxButtonStyle.Primary,
                    )
                }
                IconButton(onClick = uploader.refresh) {
                    Symbol(NxIcon.Refresh, s.profileRefresh, tint = NxTheme.colors.textSecondary)
                }
            }
            SkinUploadStatusLine(uploader.status)
        }

        Flexible("profile_sc_signout_btn", FlexibleKind.Button) {
            NxButton(
                label = s.profileSignOutSmartycraft,
                onClick = { signOut() },
                modifier = Modifier.widthIn(min = 200.dp),
                style = NxButtonStyle.Secondary,
            )
        }
        PuppetClick("account.signout.smartycraft") { signOut() }
        PuppetClick("account.logout") { ctx.onLogout() }
    }
}

@Composable
private fun AccountPanel(session: SessionData) {
    val s = LocalStrings.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Name with the status pill on its right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.playerName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = NxTheme.colors.textPrimary,
            )
            Spacer(Modifier.width(12.dp))
            StatusPill(online = session.accessToken.length > 10)
        }

        // Balance with the top-up button on its right. IntrinsicSize.Min lets
        // the button match the balance card's height (taller than a default
        // button), and a min width gives it a bit more length than its text.
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BalanceCard(session.balance, Modifier.weight(1f))
            Flexible("profile_topup_btn", FlexibleKind.Button) {
                NxButton(
                    label = s.profileTopUp,
                    onClick = { SystemActions.openUrl("https://smartycraft.ru/cabinet") },
                    modifier = Modifier.fillMaxHeight().widthIn(min = 150.dp),
                    style = NxButtonStyle.Secondary,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(online: Boolean) {
    val s = LocalStrings.current
    val accent = if (online) NxTheme.colors.success else NxTheme.colors.error
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(accent))
        Spacer(Modifier.width(7.dp))
        Text(
            text = if (online) s.profileStatusOnline else s.profileStatusOffline,
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

@Composable
private fun BalanceCard(balance: Int, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(NxTheme.colors.background.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(NxIcon.Star, s.profileBalance, tint = Color(0xFFFFD700), fill = 1f, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.profileBalance, color = NxTheme.colors.textSecondary)
        }
        Text(
            text = "$balance ⛃",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NxTheme.colors.textPrimary,
        )
    }
}
