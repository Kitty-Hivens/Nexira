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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import hivens.core.data.SessionData
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

// Account tab, skin-forward (option 7): the account panel (name + status,
// balance + top-up) sits on top, with the 3D skin and its upload/refresh
// controls directly below. Reads session from LocalProfileContext; online status
// keeps the token-length sniff from the legacy panel. Logout itself is the
// left-rail nav entry; the account.logout puppet hook below stays for automation.
@Widget(id = "profile.account.section", displayName = "widget.profile.account.section")
@Composable
fun ProfileAccountSectionWidget(instance: WidgetInstance) {
    val ctx = LocalProfileContext.current
    // The nav only mounts this slot with a session; guard defensively.
    val session = ctx.session ?: return
    val s = LocalStrings.current
    val af = LocalAprilFools.current

    // Bumped by the skin uploader so the skin re-loads after upload/refresh.
    var refreshKey by remember { mutableIntStateOf(0) }
    val uploader = rememberSkinUploader(session) { refreshKey++ }

    Column(Modifier.fillMaxSize().padding(top = 4.dp)) {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AccountPanel(session)

            // Skin below the panel, with upload + refresh directly under it.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkinHero(
                    session.playerName,
                    refreshKey,
                    Modifier.width(190.dp).height(300.dp),
                    interactive = true,
                    autoSpin = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Same Celestia-styled button as top-up (a chaos target with
                    // its original puppet id); wraps to its text on one line.
                    af.ChaosButton(
                        id = "profile_upload_skin_btn",
                        text = s.profileUploadSkin,
                        onClick = uploader.pick,
                        modifier = Modifier,
                        colors = ButtonDefaults.buttonColors(containerColor = CelestiaTheme.colors.primary),
                    )
                    IconButton(onClick = uploader.refresh) {
                        Icon(Icons.Default.Refresh, s.profileRefresh, tint = CelestiaTheme.colors.textSecondary)
                    }
                }
                SkinUploadStatusLine(uploader.status)
            }
        }
    }

    PuppetClick("account.logout") { ctx.onLogout() }
}

@Composable
private fun AccountPanel(session: SessionData) {
    val s = LocalStrings.current
    val af = LocalAprilFools.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Name with the status pill on its right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = session.playerName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = CelestiaTheme.colors.textPrimary,
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
            af.ChaosButton(
                id = "profile_topup_btn",
                text = s.profileTopUp,
                onClick = { SystemActions.openUrl("http://smartycraft.ru/cabinet") },
                modifier = Modifier.fillMaxHeight().widthIn(min = 150.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = glassSurfaceAlpha(0.55f),
                    contentColor = CelestiaTheme.colors.textPrimary,
                ),
            )
        }
    }
}

@Composable
private fun StatusPill(online: Boolean) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    val accent = if (online) CelestiaTheme.colors.success else CelestiaTheme.colors.error
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(style.cardCorner))
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
    val style = LocalStyle.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(style.cardCorner))
            .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, s.profileBalance, tint = Color(0xFFFFD700), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(s.profileBalance, color = CelestiaTheme.colors.textSecondary)
        }
        Text(
            text = "$balance ⛃",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = CelestiaTheme.colors.textPrimary,
        )
    }
}
