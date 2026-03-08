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
import androidx.compose.ui.unit.sp
import hivens.config.AppConfig
import hivens.core.api.model.ServerProfile
import hivens.ui.effects.neonBorder
import hivens.ui.effects.shimmerOverlay
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

// ─── Server color palette ─────────────────────────────────────────────────────
// Each server gets a stable unique gradient derived from its name.

private val SERVER_PALETTES = listOf(
    Pair(Color(0xFF7C3AED), Color(0xFF4F46E5)), // violet → indigo
    Pair(Color(0xFF0EA5E9), Color(0xFF6366F1)), // sky → violet
    Pair(Color(0xFF10B981), Color(0xFF0EA5E9)), // emerald → sky
    Pair(Color(0xFFF59E0B), Color(0xFFEF4444)), // amber → red
    Pair(Color(0xFFEC4899), Color(0xFF8B5CF6)), // pink → purple
    Pair(Color(0xFF14B8A6), Color(0xFF3B82F6)), // teal → blue
    Pair(Color(0xFFF97316), Color(0xFFEAB308)), // orange → yellow
    Pair(Color(0xFF6366F1), Color(0xFFEC4899)), // indigo → pink
)

private fun serverPalette(name: String): Pair<Color, Color> =
    SERVER_PALETTES[abs(name.hashCode()) % SERVER_PALETTES.size]

// ─── Card ─────────────────────────────────────────────────────────────────────

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
                try { serverIcon = ImageIO.read(iconFile)?.toComposeImageBitmap() } catch (_: Exception) {}
            }
        }
    }

    val showActions = isHovered || isFocused
    val scale by animateFloatAsState(if (showActions) 1.02f else 1.0f)
    val (colorA, colorB) = remember(profile.name) { serverPalette(profile.name) }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .let { m ->
                when {
                    isSelected -> m.neonBorder(CelestiaTheme.colors.primary, cornerRadius = 20.dp, strokeWidth = 2.dp)
                    isFocused  -> m.border(2.dp, Color.White, RoundedCornerShape(20.dp))
                    else       -> m.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
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
                                Color.Black.copy(alpha = 0.92f)
                            )
                        )
                    )
            )
        } else {
            // Generated gradient background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                colorA.copy(alpha = 0.30f),
                                colorB.copy(alpha = 0.18f)
                            )
                        )
                    )
                    .background(Color(0xFF0E0E16).copy(alpha = 0.65f))
            )
            // Subtle diagonal accent stripe
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            0f   to colorA.copy(alpha = 0.18f),
                            0.5f to Color.Transparent,
                            1f   to colorB.copy(alpha = 0.14f)
                        )
                    )
            )
        }

        // ── LAYER 2: Content ──────────────────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            if (serverIcon == null) {
                // Big abbreviation with gradient color
                Text(
                    text      = profile.name.take(2).uppercase(),
                    fontSize  = 32.sp,
                    fontWeight = FontWeight.Black,
                    color     = colorA,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
            } else {
                // If there is a picture, push the text to the very bottom
                Spacer(Modifier.weight(1f))
            }

            // Server name
            Text(
                text       = profile.title ?: profile.name,
                style      = MaterialTheme.typography.subtitle1,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                textAlign  = TextAlign.Center,
                maxLines   = 1
            )

            Spacer(Modifier.height(3.dp))

            // Version badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (serverIcon != null) Color.Black.copy(alpha = 0.4f)
                        else colorB.copy(alpha = 0.22f)
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text  = profile.version,
                    style = MaterialTheme.typography.overline,
                    color = if (serverIcon != null) Color.LightGray else colorA.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                )
            }

            if (serverIcon != null) Spacer(Modifier.height(8.dp))
        }

        // ── LAYER 3: Action buttons ───────────────────────────────────────────
        AnimatedVisibility(
            visible  = showActions,
            enter    = fadeIn() + slideInVertically { 16 },
            exit     = fadeOut() + slideOutVertically { 16 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CardIconButton(
                    icon  = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    color = if (isFavorite) Color(0xFFEF4444) else Color.White.copy(alpha = 0.8f),
                    onClick = onToggleFav
                )
                CardIconButton(Icons.Default.Settings, onClick = onSettings)
                CardIconButton(Icons.Default.Info, onClick = onDetails)
            }
        }
    }
}

@Composable
private fun CardIconButton(icon: ImageVector, color: Color = Color.White.copy(alpha = 0.8f), onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
    }
}
