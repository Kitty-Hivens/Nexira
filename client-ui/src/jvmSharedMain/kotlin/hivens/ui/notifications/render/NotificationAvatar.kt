package hivens.ui.notifications.render

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.notifications.NotifGlyph
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.NxInk

/**
 * Notification source avatar shared by the live card and the history widget:
 * a Coil image when [iconUrl] is present, else the source's [glyph] vector, else
 * a neutral package glyph (not an empty box) -- the common case until pack
 * `icon_url` is authored. Corner follows the active style.
 */
@Composable
fun NotificationAvatar(
    iconUrl: String?,
    glyph: NotifGlyph? = null,
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
) {
    val shape = RoundedCornerShape(6.dp)
    NxSurface(
        kind          = SurfaceKind.Card,
        modifier      = modifier.size(size),
        shape         = shape,
        borderWidthDp = 0f,
    ) {
        if (!iconUrl.isNullOrBlank()) {
            // Loads through the app's singleton Coil ImageLoader (set in AppShell).
            AsyncImage(
                model              = iconUrl,
                contentDescription = null,
                modifier           = Modifier.size(size).align(Alignment.Center),
                contentScale       = ContentScale.Crop,
            )
        } else {
            Symbol(icon = glyph.toVector(),
                contentDescription = null,
                modifier           = Modifier.size(size * 0.56f).align(Alignment.Center),
                tint               = NxInk.quiet,
            )
        }
    }
}

// Glyph -> vector. Null (no authored icon) keeps the neutral package fallback.
private fun NotifGlyph?.toVector(): IconKey = when (this) {
    NotifGlyph.Update -> NxIcon.CloudDownload
    null              -> NxIcon.Inventory2
}
