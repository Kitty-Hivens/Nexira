package hivens.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.config.AppConfig
import hivens.core.api.model.ServerProfile
import hivens.ui.effects.neonBorder
import hivens.ui.effects.shimmerOverlay
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO

@Composable
fun SquareServerCard(
    profile: ServerProfile,
    isSelected: Boolean,
    isFavorite: Boolean,
    onSelect: () -> Unit,
    onLaunch: () -> Unit,
    onSettings: () -> Unit,
    onDetails: () -> Unit,
    onToggleFav: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    var serverIcon by remember { mutableStateOf<ImageBitmap?>(null) }

    // Load icon from disk
    LaunchedEffect(profile) {
        withContext(Dispatchers.IO) {
            val userHome = System.getProperty("user.home")
            val os       = System.getProperty("os.name").lowercase()
            val baseDir  = when {
                os.contains("win") -> "$userHome/AppData/Roaming/${AppConfig.APP_DIR}"
                os.contains("mac") -> "$userHome/Library/Application Support/${AppConfig.APP_DIR}"
                else               -> "$userHome/${AppConfig.APP_DIR}"
            }
            // Looking for icon.png file
            val iconFile = File(baseDir, "clients/${profile.assetDir}/icon.png")
            if (iconFile.exists()) {
                try { serverIcon = ImageIO.read(iconFile)?.toComposeImageBitmap() }
                catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    val showActions = isHovered || isFocused
    val scale by animateFloatAsState(if (showActions) 1.02f else 1.0f)

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(24.dp))
            // Neon border when selected, plain when focused/default
            .let { m ->
                when {
                    isSelected -> m.neonBorder(
                        color        = CelestiaTheme.colors.primary,
                        cornerRadius = 24.dp,
                        strokeWidth  = 2.dp
                    )
                    isFocused  -> m.border(2.dp, Color.White, RoundedCornerShape(24.dp))
                    else       -> m.border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
                }
            }
            // Shimmer on hover (not when already glowing with neon)
            .shimmerOverlay(enabled = isHovered && !isSelected)
            // Interaction
            .clickable(interactionSource = interactionSource, indication = null) { onSelect() }
            .hoverable(interactionSource)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    if (isSelected) onLaunch() else onSelect()
                    true
                } else false
            }
    ) {
        // ── LAYER 1: Background ───────────────────────────────────────────────
        if (serverIcon != null) {
            // If there is a picture, draw it across the entire background.
            Image(
                painter      = BitmapPainter(serverIcon!!),
                contentDescription = null,
                modifier     = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            // Gradient scrim so text is readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.5f),
                                Color.Black.copy(alpha = 0.9f)
                            )
                        )
                    )
            )
        } else {
            // If there is no picture, draw a standard abstract gradient
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = if (isSelected)
                                listOf(Color(0xFF252535).copy(alpha = 0.9f), Color(0xFF15151A).copy(alpha = 0.95f))
                            else
                                listOf(Color(0xFF202025).copy(alpha = 0.6f), Color(0xFF101012).copy(alpha = 0.7f))
                        )
                    )
            )
        }

        // ── LAYER 2: Text content ─────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            if (serverIcon == null) {
                // Show a circle with letters ONLY if there is no picture
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(CelestiaTheme.colors.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = profile.name.take(2).uppercase(),
                        style = MaterialTheme.typography.h4,
                        color = CelestiaTheme.colors.primary
                    )
                }
                Spacer(Modifier.height(16.dp))
            } else {
                // If there is a picture, push the text to the very bottom
                Spacer(Modifier.weight(1f))
            }

            // Server name
            Text(
                text      = profile.title ?: profile.name,
                style     = MaterialTheme.typography.h6,
                fontWeight = FontWeight.Bold,
                color     = Color.White,
                textAlign = TextAlign.Center,
                maxLines  = 1
            )

            Spacer(Modifier.height(4.dp))

            // Version
            Text(
                text  = profile.version,
                style = MaterialTheme.typography.caption,
                color = if (serverIcon != null) Color.LightGray else Color.Gray
            )
            if (serverIcon != null) Spacer(Modifier.height(8.dp))
        }

        // ── LAYER 3: Action buttons ───────────────────────────────────────────
        AnimatedVisibility(
            visible  = showActions,
            enter    = fadeIn() + slideInVertically { 20 },
            exit     = fadeOut() + slideOutVertically { 20 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HoverIconButton(
                    icon  = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    color = if (isFavorite) Color.Red else Color.White,
                    onClick = onToggleFav
                )
                HoverIconButton(Icons.Default.Settings, onClick = onSettings)
                HoverIconButton(Icons.Default.Info,     onClick = onDetails)
            }
        }
    }
}

@Composable
private fun HoverIconButton(icon: ImageVector, color: Color = Color.White, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, null, tint = color.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
    }
}
