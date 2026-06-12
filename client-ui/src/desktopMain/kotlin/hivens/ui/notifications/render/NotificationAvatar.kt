package hivens.ui.notifications.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

/**
 * Notification source avatar shared by the live card and the history widget:
 * a Coil image when [iconUrl] is present, else a neutral package glyph (not an
 * empty box) for sources without an icon -- the common case until pack
 * `icon_url` is authored. Corner follows the active style.
 */
@Composable
fun NotificationAvatar(iconUrl: String?, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val palette = CelestiaTheme.colors
    val style = LocalStyle.current
    val shape = RoundedCornerShape((style.cardCorner / 2).coerceAtMost(8.dp))
    Box(
        modifier         = modifier
            .size(size)
            .clip(shape)
            .background(palette.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (!iconUrl.isNullOrBlank()) {
            // Loads through the app's singleton Coil ImageLoader (set in AppShell).
            AsyncImage(
                model              = iconUrl,
                contentDescription = null,
                modifier           = Modifier.size(size),
                contentScale       = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector        = Icons.Outlined.Inventory2,
                contentDescription = null,
                modifier           = Modifier.size(size * 0.56f),
                tint               = palette.textSecondary,
            )
        }
    }
}
