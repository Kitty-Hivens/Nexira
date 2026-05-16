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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.model.ServerProfile
import hivens.launcher.AutoSyncService
import hivens.launcher.platform.PlatformPaths
import hivens.ui.debug.SkiaTracker
import hivens.ui.easter.AprilFools
import hivens.ui.easter.ChaosState
import hivens.ui.easter.FloatingButton
import hivens.ui.effects.neonBorder
import hivens.ui.effects.shimmerOverlay
import hivens.ui.theme.CelestiaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

// ─── Server color palette ─────────────────────────────────────────────────────
// Each server gets a stable unique gradient derived from its name.

private val SERVER_PALETTES = listOf(
    Pair(Color(0xFF7C3AED), Color(0xFF4F46E5)), // violet -> indigo
    Pair(Color(0xFF0EA5E9), Color(0xFF6366F1)), // sky -> violet
    Pair(Color(0xFF10B981), Color(0xFF0EA5E9)), // emerald -> sky
    Pair(Color(0xFFF59E0B), Color(0xFFEF4444)), // amber -> red
    Pair(Color(0xFFEC4899), Color(0xFF8B5CF6)), // pink -> purple
    Pair(Color(0xFF14B8A6), Color(0xFF3B82F6)), // teal -> blue
    Pair(Color(0xFFF97316), Color(0xFFEAB308)), // orange -> yellow
    Pair(Color(0xFF6366F1), Color(0xFFEC4899)), // indigo -> pink
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
    onToggleFav: () -> Unit,
    syncState: AutoSyncService.ServerState? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isFocused by interactionSource.collectIsFocusedAsState()

    var serverIcon by remember { mutableStateOf<ImageBitmap?>(null) }
    val paths: PlatformPaths = koinInject()

    // Load icon from disk
    LaunchedEffect(profile) {
        withContext(Dispatchers.IO) {
            val iconFile = paths.clientDir(profile.assetDir).resolve("icon.png").toFile()
            if (iconFile.exists()) {
                runCatching {
                    serverIcon = ImageIO.read(iconFile)?.toComposeImageBitmap()?.also {
                        SkiaTracker.track("Card.icon[${profile.assetDir}]", it)
                    }
                }
            }
        }
    }

    // ── April Fools: register card as a chaos target ──────────────────────────
    // The card is a Box, not a Button, so we register it manually as a FloatingButton.
    // The engine can then yank it out of the grid and send it flying.
    val chaosId = "server_card_${profile.assetDir}"
    val chaosBtn = remember(profile.assetDir) {
        ChaosState.find(chaosId) ?: FloatingButton(
            id       = chaosId,
            label    = profile.title ?: profile.name,
            widthPx  = 200f,
            heightPx = 200f,
            onClick  = onSelect,
        ).also { btn -> if (AprilFools.isActive()) ChaosState.register(btn) }
    }

    // Keep onClick in sync (server list can refresh)
    LaunchedEffect(onSelect) {
        if (AprilFools.isActive()) chaosBtn.onClick = onSelect
    }

    DisposableEffect(profile.assetDir) {
        onDispose { if (AprilFools.isActive()) ChaosState.unregister(chaosId) }
    }

    val showActions = isHovered || isFocused
    val scale by animateFloatAsState(if (showActions) 1.02f else 1.0f)
    val (colorA, colorB) = remember(profile.name) { serverPalette(profile.name) }

    // ── Theme-aware overlay colors ─────────────────────────────────────────
    val bgBase      = CelestiaTheme.colors.background
    val surfaceBase = CelestiaTheme.colors.surface
    // In dark theme bg is near-black; in light theme it's light gray -- both look correct.
    val cardOverlay     = bgBase.copy(alpha = 0.65f)
    val actionBarColor  = surfaceBase.copy(alpha = 0.92f)
    val scrimMid        = bgBase.copy(alpha = 0.50f)
    val scrimBottom     = bgBase.copy(alpha = 0.92f)
    val badgeBgFallback = colorB.copy(0.22f)

    // When the chaos engine has taken control, the original card is invisible
    // and the clone lives in ChaosOverlay -- block all pointer events on the ghost.
    val isChaosEscaped = AprilFools.isActive() && !chaosBtn.originalVisible
    val chaosBlocker: Modifier = if (isChaosEscaped) {
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                        .changes.forEach { it.consume() }
                }
            }
        }
    } else {
        Modifier
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
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .let { m ->
                when {
                    isSelected -> m.neonBorder(CelestiaTheme.colors.primary, cornerRadius = 20.dp, strokeWidth = 2.dp)
                    isFocused  -> m.border(2.dp, CelestiaTheme.colors.textPrimary, RoundedCornerShape(20.dp))
                    else       -> m.border(1.dp, CelestiaTheme.colors.outline.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                }
            }
            // Shimmer on hover (not when already glowing with neon)
            .shimmerOverlay(enabled = isHovered && !isSelected)
            // Track position for chaos engine
            .onGloballyPositioned { coords ->
                if (AprilFools.isActive()) chaosBtn.originPx = coords.positionInWindow()
            }
            // Hide original when chaos engine has taken control
            .graphicsLayer {
                alpha = if (isChaosEscaped) 0f else 1f
            }
            // Block clicks on invisible original so the overlay clone handles them
            .then(chaosBlocker)
    ) {
        // ── LAYER 1: Background ───────────────────────────────────────────────
        if (serverIcon != null) {
            // If there is a picture, draw it across the entire background.
            Image(
                painter            = BitmapPainter(serverIcon!!),
                contentDescription = null,
                modifier           = Modifier.fillMaxSize(),
                contentScale       = ContentScale.Crop
            )
            // Gradient scrim -- uses theme background color so it blends in both themes
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                scrimMid,
                                scrimBottom
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
                    .background(cardOverlay)
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
            modifier            = Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            if (serverIcon == null) {
                // Big abbreviation with gradient color
                Text(
                    text       = profile.name.take(2).uppercase(),
                    fontSize   = 32.sp,
                    fontWeight = FontWeight.Black,
                    color      = colorA,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
            } else {
                // If there is a picture, push the text to the very bottom
                Spacer(Modifier.weight(1f))
            }

            Text(
                text       = profile.title ?: profile.name,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color      = CelestiaTheme.colors.textPrimary,
                textAlign  = TextAlign.Center,
                maxLines   = 1
            )

            Spacer(Modifier.height(3.dp))

            // Version badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (serverIcon != null) surfaceBase.copy(0.55f) else badgeBgFallback
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text       = profile.version,
                    style      = MaterialTheme.typography.labelSmall,
                    color      = if (serverIcon != null) CelestiaTheme.colors.textSecondary else colorA.copy(0.9f),
                    fontWeight = FontWeight.Medium
                )
            }

            if (serverIcon != null) Spacer(Modifier.height(8.dp))
        }

        // ── LAYER 3: Action buttons ───────────────────────────────────────────
        // ── Sync state badge (top-right) ──────────────────────────────────────
        // Shown only when AutoSyncService has touched this server in the
        // current session. Hidden after a brief grace period for SYNCED so
        // the dashboard doesn't get permanently dotted with green checkmarks.
        if (syncState != null && syncState != AutoSyncService.ServerState.SKIPPED) {
            SyncBadge(
                state = syncState,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }

        AnimatedVisibility(
            visible  = showActions && !isChaosEscaped,
            enter    = fadeIn() + slideInVertically { 16 },
            exit     = fadeOut() + slideOutVertically { 16 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(actionBarColor)
                    .border(1.dp, CelestiaTheme.colors.outline.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CardIconButton(
                    icon  = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    color = if (isFavorite) Color(0xFFEF4444) else CelestiaTheme.colors.textSecondary,
                    onClick = onToggleFav
                )
                CardIconButton(Icons.Default.Settings, color = CelestiaTheme.colors.textSecondary, onClick = onSettings)
                CardIconButton(Icons.Default.Info,     color = CelestiaTheme.colors.textSecondary, onClick = onDetails)
            }
        }
    }
}

@Composable
private fun CardIconButton(icon: ImageVector, color: Color = Color.White.copy(0.8f), onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
    }
}

/**
 * Tiny pill-shaped badge for AutoSyncService state. Lives in the card's top-right corner.
 * Auto-hides 5 seconds after entering SYNCED so the dashboard
 * doesn't stay decorated with green checkmarks across sessions.
 */
@Composable
private fun SyncBadge(state: AutoSyncService.ServerState, modifier: Modifier = Modifier) {
    var visible by remember(state) { mutableStateOf(true) }

    // Fade out SYNCED after a grace window. QUEUED/SYNCING/FAILED stay until
    // the next sync cycle replaces or clears them.
    LaunchedEffect(state) {
        if (state == AutoSyncService.ServerState.SYNCED) {
            kotlinx.coroutines.delay(5000.milliseconds)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val (icon, tint) = when (state) {
            AutoSyncService.ServerState.QUEUED   -> Icons.Default.HourglassEmpty to Color(0xFFEAB308)  // amber
            AutoSyncService.ServerState.SYNCING  -> Icons.Default.Sync           to Color(0xFF38BDF8)  // sky
            AutoSyncService.ServerState.SYNCED   -> Icons.Default.Check          to Color(0xFF22C55E)  // green
            AutoSyncService.ServerState.FAILED   -> Icons.Default.Close          to Color(0xFFEF4444)  // red
            AutoSyncService.ServerState.SKIPPED  -> return@AnimatedVisibility
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.18f))
                .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}
