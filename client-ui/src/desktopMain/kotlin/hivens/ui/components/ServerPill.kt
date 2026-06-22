package hivens.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.model.ServerProfile
import hivens.launcher.platform.PlatformPaths
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Compact full-width capsule row for a server -- the narrow-width alternative to
 * [SquareServerCard]. One per row, stacked vertically. Click selects, Enter
 * launches the selected one; fav/settings/details surface on hover. Shows the
 * server's icon.png when present (same source as the square card), else a
 * gradient two-letter avatar.
 */
@Composable
fun ServerPill(
    profile: ServerProfile,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onLaunch: () -> Unit,
    onSettings: () -> Unit,
    onDetails: () -> Unit,
    onToggleFav: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isFocused by interaction.collectIsFocusedAsState()
    val showActions = isHovered || isFocused
    val palette = NxTheme.colors
    val (colorA, colorB) = remember(profile.name, palette) { palette.decorativePair(profile.name) }

    var serverIcon by remember(profile.assetDir) { mutableStateOf<ImageBitmap?>(null) }
    val paths: PlatformPaths = koinInject()
    LaunchedEffect(profile.assetDir) {
        withContext(Dispatchers.IO) {
            val iconFile = paths.clientDir(profile.assetDir).resolve("icon.png").toFile()
            if (iconFile.exists()) {
                runCatching { serverIcon = ImageIO.read(iconFile)?.toComposeImageBitmap() }
            }
        }
    }

    // Clear focus when a press is cancelled (press, then drag away) so the focus
    // frame does not linger on a card the user did not actually pick.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(interaction) {
        interaction.interactions.collect { i ->
            if (i is PressInteraction.Cancel) focusManager.clearFocus()
        }
    }

    val borderColor = when {
        isSelected -> NxTheme.colors.primary
        isFocused  -> NxTheme.colors.textPrimary
        else       -> NxTheme.colors.outline.copy(alpha = 0.25f)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.45f))
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, MaterialTheme.shapes.medium)
            .clickable(interactionSource = interaction, indication = null) { onSelect() }
            .hoverable(interaction)
            .focusable(interactionSource = interaction)
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp && (ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                    if (isSelected) onLaunch() else onSelect()
                    true
                } else false
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (serverIcon == null) {
                        Modifier.background(
                            Brush.linearGradient(listOf(colorA.copy(alpha = 0.85f), colorB.copy(alpha = 0.70f))),
                        )
                    } else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val icon = serverIcon
            if (icon != null) {
                Image(
                    painter            = BitmapPainter(icon),
                    contentDescription = null,
                    modifier           = Modifier.fillMaxSize(),
                    contentScale       = ContentScale.Crop,
                )
            } else {
                Text(
                    text       = profile.name.take(2).uppercase(),
                    fontSize   = 13.sp,
                    fontWeight = FontWeight.Black,
                    color      = Color.White,
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text       = profile.title ?: profile.name,
                style      = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color      = NxTheme.colors.textPrimary,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            Text(
                text     = profile.version,
                style    = MaterialTheme.typography.labelSmall,
                color    = NxTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        AnimatedVisibility(visible = showActions, enter = fadeIn(), exit = fadeOut()) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                PillIconButton(
                    icon  = NxIcon.Favorite,
                    tint  = if (isFavorite) Color(0xFFEF4444) else NxTheme.colors.textSecondary,
                    fill  = if (isFavorite) 1f else 0f,
                    onClick = onToggleFav,
                )
                PillIconButton(NxIcon.Settings, tint = NxTheme.colors.textSecondary, onClick = onSettings)
                PillIconButton(NxIcon.Info, tint = NxTheme.colors.textSecondary, onClick = onDetails)
            }
        }
    }
}

@Composable
private fun PillIconButton(icon: IconKey, tint: Color, onClick: () -> Unit, fill: Float = 0f) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Symbol(icon, contentDescription = null, tint = tint, fill = fill, modifier = Modifier.size(15.dp))
    }
}
