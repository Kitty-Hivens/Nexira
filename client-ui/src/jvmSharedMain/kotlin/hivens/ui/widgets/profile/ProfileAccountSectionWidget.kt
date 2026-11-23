package hivens.ui.widgets.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.AccountView
import hivens.ui.LoginPanel
import hivens.ui.components.rememberSignOutRequest
import hivens.ui.i18n.nexiraStrings
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.skin3d.Cycles
import hivens.ui.skin3d.rememberSkinViewState
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

private const val SC_KEY = AccountKeys.SMARTYCRAFT

/** From this width the balance and its top-up button share a line. */
private val SIDE_BY_SIDE_FROM = 420.dp

// SmartyCraft profile section (slot "account"). Signed into SmartyCraft it is the
// skin-forward account view -- name + status, balance + top-up, the 3D skin and
// its upload/refresh -- with a sign-out. Signed out of SmartyCraft (but present
// in the shell via another provider) it shows the SmartyCraft sign-in form.
// Resolves the SmartyCraft account directly rather than the shell face, since the
// face may belong to a different provider; Microsoft lives in its own section.
@Widget(
    id = "profile.account.section",
    displayName = "widget.profile.account.section",
    needs = [
        "accounts.list", "accounts.signIns", "accounts.providers", "accounts.remember", "accounts.remember.set",
        "auth.signIn.smartycraft", "auth.signIn.offline", "auth.signIn.code", "auth.signIn.cancel",
        "accounts.remove", "accounts.removals", "auth.signOut", "accounts.skin.upload",
    ],
)
@Composable
fun ProfileAccountSectionWidget() {
    // Nothing until the store has answered: drawing the sign-in form meanwhile
    // would offer to sign in an account that is signed in. The list is the app's,
    // so the nav's face picker hears about a sign-out that happens here.
    val accounts = rememberScopedSource(AccountKeys.Accounts).valueOrNull() ?: return
    val sc = accounts.firstOrNull { it.providerKey == SC_KEY }

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.widthIn(max = 520.dp)) {
            if (sc == null) {
                // SmartyCraft uses the username/password form (plus offline); the
                // Microsoft button is suppressed, since it has its own section. A sign-in
                // that works signs the launcher in, as the face the accounts give.
                LoginPanel(showMicrosoft = false)
            } else {
                SmartyCraftAccount(sc, othersLeft = accounts.size > 1)
            }
        }
    }
}

@Composable
private fun SmartyCraftAccount(account: AccountView, othersLeft: Boolean) {
    // Signing out of SmartyCraft removes its account. If it was the only one, or no
    // face is left after it, that is a full logout, which asks first through the
    // window's sign-out question, so a dismissed dialog leaves the account intact.
    // The question stays open when this section leaves once its account is gone.
    val signOut = rememberAccountSignOut(SC_KEY, othersLeft) ?: return
    // Automation's full logout asks the window's question directly.
    val signOutAll = rememberSignOutRequest() ?: return
    val s = nexiraStrings()

    // Bumped by the skin uploader so the skin re-loads after upload/refresh.
    var skinKey by remember { mutableIntStateOf(0) }
    val uploader = rememberSkinUploader(SC_KEY, account.name) { skinKey++ }

    // Its own scroll: a phone has less height than the page needs, and nothing in it is lazy, so
    // unbounded height is safe here (see the note on the surface).
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccountPanel(account)

        // Skin below the panel, with upload + refresh directly under it. The
        // hero idles (breath + head drift) instead of the turntable spin --
        // the character inhabits the page; drag still orbits for inspection.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkinHero(
                account.name,
                skinKey,
                Modifier.width(190.dp).height(300.dp),
                interactive = true,
                autoSpin = false,
                state = rememberSkinViewState(initialAnimation = Cycles.idle()),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NxButton(
                    label = s.text("profileUploadSkin"),
                    onClick = uploader.pick,
                    style = NxButtonStyle.Primary,
                )
                IconButton(onClick = uploader.refresh) {
                    Symbol(NxIcon.Refresh, s.text("profileRefresh"), tint = NxInk.quiet)
                }
            }
            SkinUploadStatusLine(uploader.status)
        }

        NxButton(
            label = s.text("profileSignOutSmartycraft"),
            onClick = signOut,
            modifier = Modifier.widthIn(min = 200.dp),
            style = NxButtonStyle.Secondary,
        )
        PuppetClick("account.signout.smartycraft") { signOut() }
        PuppetClick("account.logout") { signOutAll() }
    }
}

@Composable
private fun AccountPanel(account: AccountView) {
    val s = nexiraStrings()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Name with the status pill on its right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = account.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = NxInk.main,
            )
            Spacer(Modifier.width(12.dp))
            StatusPill(online = account.online)
        }

        // Balance with the top-up button on its right. IntrinsicSize.Min lets
        // the button match the balance card's height (taller than a default
        // button), and a min width gives it a bit more length than its text.
        // Where the two would squeeze the label out of the card, the button goes under it.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val topUp: @Composable (Modifier) -> Unit = { modifier ->
                NxButton(
                    label = s.text("profileTopUp"),
                    onClick = { SystemActions.openUrl("https://smartycraft.ru/cabinet") },
                    modifier = modifier,
                    style = NxButtonStyle.Secondary,
                )
            }
            if (maxWidth < SIDE_BY_SIDE_FROM) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BalanceCard(account.balance, Modifier.fillMaxWidth())
                    topUp(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BalanceCard(account.balance, Modifier.weight(1f))
                    topUp(Modifier.fillMaxHeight().widthIn(min = 150.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusPill(online: Boolean) {
    val s = nexiraStrings()
    val accent = if (online) NxColor.status(Status.Success) else NxColor.status(Status.Error)
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
            text = if (online) s.text("profileStatusOnline") else s.text("profileStatusOffline"),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

@Composable
private fun BalanceCard(balance: Int, modifier: Modifier = Modifier) {
    val s = nexiraStrings()
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(NxColor.page.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            Symbol(NxIcon.Star, s.text("profileBalance"), tint = Color(0xFFFFD700), fill = 1f, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.text("profileBalance"), color = NxInk.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        // One line, never wrapped: the figure and its coin broke apart when the card was narrow.
        Text(
            text = "$balance ⛃",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = NxInk.main,
            maxLines = 1,
            softWrap = false,
        )
    }
}
