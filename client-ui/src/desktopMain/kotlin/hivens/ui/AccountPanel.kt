package hivens.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import androidx.compose.ui.text.style.TextOverflow
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.SkinManager
import org.koin.compose.koinInject

@Composable
fun AccountPanel(session: SessionData, onLogout: () -> Unit) {
    val skinManager: SkinManager = koinInject()
    val s = LocalStrings.current
    var faceBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(session.playerName) {
        faceBitmap = skinManager.getSkinFront(session.playerName)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Face
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(CelestiaTheme.colors.surface),
            contentAlignment = Alignment.TopCenter
        ) {
            if (faceBitmap != null) {
                Image(
                    painter            = BitmapPainter(faceBitmap!!),
                    contentDescription = null,
                    modifier           = Modifier.size(38.dp),
                    contentScale       = ContentScale.Crop,
                    alignment          = Alignment.TopCenter
                )
            } else {
                Text(
                    text     = session.playerName.take(1).uppercase(),
                    color    = CelestiaTheme.colors.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = session.playerName,
                style      = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color      = CelestiaTheme.colors.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text  = s.profileStatusOnline,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.success
            )
        }

        // Logout is NOT chaos-wrapped -- user must always be able to log out
        TextButton(
            onClick  = onLogout,
            modifier = Modifier.height(30.dp)
        ) {
            Text(
                text  = s.navLogout,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.error.copy(alpha = 0.65f)
            )
        }
        // Puppet: secondary logout entry-point (the other is the sidebar
        // ExitToApp icon, which is `nav.logout`). Kept distinct because a
        // regression could hit just one of them -- e.g. a layout change
        // that detaches the right-panel logout but leaves the sidebar.
        PuppetClick("account.logout") { onLogout() }
    }
}

// ─── Auth loading slot (used by RightPanel when AppState is Loading) ────────

@Composable
internal fun AuthLoadingSlot() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color       = CelestiaTheme.colors.primary.copy(alpha = 0.35f),
            modifier    = Modifier.size(22.dp),
            strokeWidth = 2.dp
        )
    }
}
