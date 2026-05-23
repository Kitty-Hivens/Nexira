package hivens.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.platform.SystemActions
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

/**
 * Account info + balance + top-up action. Status pill turns
 * success/error based on whether the session looks online (token
 * length sniff).
 */
@Composable
internal fun AccountSection(session: SessionData) {
    val s = LocalStrings.current
    val af = LocalAprilFools.current
    val style = LocalStyle.current

    PuppetClick("profile.topUp") {
        SystemActions.openUrl("http://smartycraft.ru/cabinet")
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text  = session.playerName,
            style = MaterialTheme.typography.headlineSmall,
            color = CelestiaTheme.colors.textPrimary,
        )

        val isOnline = session.accessToken.length > 10
        Text(
            text  = "${s.profileStatusLabel}: " +
                if (isOnline) s.profileStatusOnline else s.profileStatusOffline,
            color = if (isOnline) CelestiaTheme.colors.success else CelestiaTheme.colors.error,
        )

        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(style.cardCorner))
                .background(CelestiaTheme.colors.background.copy(alpha = 0.4f))
                .padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, s.profileBalance, tint = Color(0xFFFFD700), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(s.profileBalance, color = CelestiaTheme.colors.textSecondary)
            }
            Text(
                text       = "${session.balance} ⛃",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color      = CelestiaTheme.colors.textPrimary,
            )
        }

        Spacer(Modifier.height(8.dp))

        af.ChaosButton(
            id       = "profile_topup_btn",
            text     = s.profileTopUp,
            onClick  = { SystemActions.openUrl("http://smartycraft.ru/cabinet") },
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(
                containerColor = CelestiaTheme.colors.primary,
            ),
        )
    }
}
