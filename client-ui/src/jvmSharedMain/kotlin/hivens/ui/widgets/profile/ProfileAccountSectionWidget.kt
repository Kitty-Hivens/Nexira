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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackAuthRequirement
import hivens.core.data.releasingFace
import hivens.core.data.SessionData
import hivens.auth.AccountStore
import hivens.ui.LoginPanel
import hivens.ui.components.rememberSignOutRequest
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
import hivens.ui.utils.rememberReadOffMain
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import hivens.widget.model.Widget
import org.koin.compose.koinInject
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

private const val SC_KEY = PackAuthRequirement.SmartyCraft.PROVIDER_KEY

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
    needs = ["app.state", "accounts.revision", "accounts.changed", "auth.signIn", "auth.signOut"],
)
@Composable
fun ProfileAccountSectionWidget() {
    val face = rememberSession()
    val signIn = rememberScopedCommand(Commands.SignIn) ?: return
    val changed = rememberScopedAction(Commands.AccountsChanged) ?: return
    val credentials: AccountStore = koinInject()
    val settingsService: ISettingsService = koinInject()
    val scope = rememberCoroutineScope()

    // The app's revision rather than a key of this section's own: the nav's face
    // picker has to hear about a sign-out that happens here.
    val revision = rememberScopedSource(Sources.AccountsRevision).valueOrNull() ?: return
    // Nothing until the store has answered: drawing the sign-in form meanwhile
    // would offer to sign in an account that is signed in.
    val scSession = (rememberReadOffMain(revision, face) { credentials.accountFor(SC_KEY) } ?: return).value

    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.widthIn(max = 520.dp)) {
            if (scSession == null) {
                // SmartyCraft uses the username/password form (plus offline); the
                // Microsoft button is suppressed -- it has its own section.
                LoginPanel(
                    onLogin = { session ->
                        // An offline identity is never stored as an account, so looking
                        // the face up again would find nothing and drop the sign-in.
                        if (session.offline) {
                            signIn(session)
                            changed()
                        } else {
                            scope.launch {
                                withContext(Dispatchers.IO) { credentials.faceSession(settingsService) }
                                    ?.let(signIn)
                                changed()
                            }
                        }
                    },
                    showMicrosoft = false,
                )
            } else {
                SmartyCraftAccount(scSession, changed)
            }
        }
    }
}

@Composable
private fun SmartyCraftAccount(session: SessionData, onChanged: () -> Unit) {
    val signIn = rememberScopedCommand(Commands.SignIn) ?: return
    // A full sign-out asks first, through the window's sign-out question, which
    // stays open when this section leaves once its account is gone.
    val signOutAll = rememberSignOutRequest() ?: return
    val credentials: AccountStore = koinInject()
    val settingsService: ISettingsService = koinInject()
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    var signingOut by remember { mutableStateOf(false) }

    // Bumped by the skin uploader so the skin re-loads after upload/refresh.
    var skinKey by remember { mutableIntStateOf(0) }
    val uploader = rememberSkinUploader(session) { skinKey++ }

    // Signing out of SmartyCraft removes its account; if it was the only one, that
    // is a full logout -- route it through the confirm (which clears + signs out)
    // so a dismissed dialog leaves the account intact.
    //
    // One at a time: a second press while the first is still at the store would
    // find one account left and ask to sign that one out too.
    fun signOut() {
        if (signingOut) return
        signingOut = true
        scope.launch {
            try {
                val accounts = withContext(Dispatchers.IO) { credentials.listAccounts() }
                if (accounts.size <= 1) {
                    signOutAll()
                    return@launch
                }
                val face = withContext(Dispatchers.IO) {
                    accounts.firstOrNull { it.providerId == SC_KEY }
                        ?.let { credentials.removeAccount(it.providerId, it.accountId) }
                    // The face choice goes with the account it named -- see releasingFace.
                    settingsService.updateSettings { it.releasingFace(SC_KEY) }
                    credentials.faceSession(settingsService)
                }
                face?.let(signIn) ?: signOutAll()
                onChanged()
            } finally {
                signingOut = false
            }
        }
    }

    // Its own scroll: a phone has less height than the page needs, and nothing in it is lazy, so
    // unbounded height is safe here (see the note on the surface).
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
                    Symbol(NxIcon.Refresh, s.profileRefresh, tint = NxInk.quiet)
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
        PuppetClick("account.logout") { signOutAll() }
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
                color = NxInk.main,
            )
            Spacer(Modifier.width(12.dp))
            StatusPill(online = session.accessToken.length > 10)
        }

        // Balance with the top-up button on its right. IntrinsicSize.Min lets
        // the button match the balance card's height (taller than a default
        // button), and a min width gives it a bit more length than its text.
        // Where the two would squeeze the label out of the card, the button goes under it.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val topUp: @Composable (Modifier) -> Unit = { modifier ->
                Flexible("profile_topup_btn", FlexibleKind.Button) {
                    NxButton(
                        label = s.profileTopUp,
                        onClick = { SystemActions.openUrl("https://smartycraft.ru/cabinet") },
                        modifier = modifier,
                        style = NxButtonStyle.Secondary,
                    )
                }
            }
            if (maxWidth < SIDE_BY_SIDE_FROM) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BalanceCard(session.balance, Modifier.fillMaxWidth())
                    topUp(Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BalanceCard(session.balance, Modifier.weight(1f))
                    topUp(Modifier.fillMaxHeight().widthIn(min = 150.dp))
                }
            }
        }
    }
}

@Composable
private fun StatusPill(online: Boolean) {
    val s = LocalStrings.current
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
            .background(NxColor.page.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically) {
            Symbol(NxIcon.Star, s.profileBalance, tint = Color(0xFFFFD700), fill = 1f, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.profileBalance, color = NxInk.quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
