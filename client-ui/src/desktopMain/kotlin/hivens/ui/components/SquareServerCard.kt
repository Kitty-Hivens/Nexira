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
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.core.api.model.ServerProfile
import hivens.launcher.AutoSyncService
import hivens.launcher.platform.PlatformPaths
import hivens.ui.easter.LocalAprilFools
import hivens.ui.effects.neonBorder
import hivens.ui.effects.shimmerOverlay
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.decorativePair
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// ─── Card ─────────────────────────────────────────────────────────────────────
// The per-server gradient is derived from NxColors.decorativePair, keyed on
// the server name, so it follows the active theme ramp.

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

    // A press that ends off the card (press, then drag to another card) leaves
    // this card focused, so its focus frame lingers. Clear focus when the press
    // is cancelled so the frame leaves with the pointer. A real click selects
    // the card and the selection border wins, so keyboard focus is untouched.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Cancel) focusManager.clearFocus()
        }
    }

    var serverIcon by remember { mutableStateOf<ImageBitmap?>(null) }
    val paths: PlatformPaths = koinInject()
    val af = LocalAprilFools.current

    // Load icon from disk
    LaunchedEffect(profile) {
        withContext(Dispatchers.IO) {
            val iconFile = paths.clientDir(profile.assetDir).resolve("icon.png").toFile()
            if (iconFile.exists()) {
                runCatching {
                    serverIcon = ImageIO.read(iconFile)?.toComposeImageBitmap()
                }
            }
        }
    }

    // ── April Fools: register card as a chaos target ──────────────────────────
    // The card is a Box, not a Button, so we acquire a tracker from the chaos
    // lifecycle and let it decide whether to actually register with the engine.
    // NoOp impl returns a stub tracker -- `originalVisible` is permanently
    // true, mutators no-op -- so production builds never touch chaos state.
    val chaosId = "server_card_${profile.assetDir}"
    val tracker = remember(profile.assetDir) {
        af.acquireCardTracker(
            id       = chaosId,
            label    = profile.title ?: profile.name,
            widthPx  = 200f,
            heightPx = 200f,
            onClick  = onSelect,
        )
    }

    // Keep onClick in sync (server list can refresh).
    LaunchedEffect(onSelect) {
        tracker.setOnClick(onSelect)
    }

    DisposableEffect(profile.assetDir) {
        onDispose { tracker.release() }
    }

    val showActions = isHovered || isFocused
    val scale by animateFloatAsState(if (showActions) 1.02f else 1.0f)
    val palette = NxTheme.colors
    val (colorA, colorB) = remember(profile.name, palette) { palette.decorativePair(profile.name) }

    // ── Theme-aware overlay colors ─────────────────────────────────────────
    val bgBase      = NxTheme.colors.background
    val surfaceBase = NxTheme.colors.surface
    // In dark theme bg is near-black; in light theme it's light gray -- both look correct.
    val cardOverlay     = bgBase.copy(alpha = 0.65f)
    val actionBarColor  = surfaceBase.copy(alpha = 0.92f)
    val scrimMid        = bgBase.copy(alpha = 0.50f)
    val scrimBottom     = bgBase.copy(alpha = 0.92f)
    val badgeBgFallback = colorB.copy(0.22f)

    // When the chaos engine has taken control, the original card is invisible
    // and the clone lives in ChaosOverlay -- block all pointer events on the ghost.
    val isChaosEscaped = af.isActive() && !tracker.originalVisible
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
            .clip(MaterialTheme.shapes.medium)
            .let { m ->
                when {
                    // With decorative motion off the selection frame is a static
                    // border rather than an animated pulse, the same gate every
                    // decorative effect takes.
                    isSelected ->
                        m.neonBorder(NxTheme.colors.primary, cornerRadius = 12.dp, strokeWidth = 2.dp)
                    isSelected -> m.border(2.dp, NxTheme.colors.primary, MaterialTheme.shapes.medium)
                    isFocused  -> m.border(2.dp, NxTheme.colors.textPrimary, MaterialTheme.shapes.medium)
                    else       -> m.border(1.dp, NxTheme.colors.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
                }
            }
            // Shimmer on hover (not when already glowing with neon), and only
            // where decorative motion renders at all.
            .shimmerOverlay(enabled = isHovered && !isSelected)
            // Track position for chaos engine. Tracker setter is a no-op
            // in production builds (NoOp impl), so no `if active` guard.
            .onGloballyPositioned { coords ->
                tracker.setOrigin(coords.positionInWindow())
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
                color      = NxTheme.colors.textPrimary,
                textAlign  = TextAlign.Center,
                maxLines   = 1
            )

            Spacer(Modifier.height(3.dp))

            // Version badge
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(
                        if (serverIcon != null) surfaceBase.copy(0.55f) else badgeBgFallback
                    )
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text(
                    text       = profile.version,
                    style      = MaterialTheme.typography.labelSmall,
                    color      = if (serverIcon != null) NxTheme.colors.textSecondary else colorA.copy(0.9f),
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
                    .clip(MaterialTheme.shapes.medium)
                    .background(actionBarColor)
                    .border(1.dp, NxTheme.colors.outline.copy(alpha = 0.3f), MaterialTheme.shapes.medium)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CardIconButton(
                    icon  = NxIcon.Favorite,
                    color = if (isFavorite) Color(0xFFEF4444) else NxTheme.colors.textSecondary,
                    fill  = if (isFavorite) 1f else 0f,
                    onClick = onToggleFav
                )
                CardIconButton(NxIcon.Settings, color = NxTheme.colors.textSecondary, onClick = onSettings)
                CardIconButton(NxIcon.Info,     color = NxTheme.colors.textSecondary, onClick = onDetails)
            }
        }
    }
}

@Composable
private fun CardIconButton(icon: IconKey, color: Color = Color.White.copy(0.8f), onClick: () -> Unit, fill: Float = 0f) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Symbol(icon, null, tint = color, fill = fill, modifier = Modifier.size(16.dp))
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
        val c = NxTheme.colors
        val (icon, tint) = when (state) {
            AutoSyncService.ServerState.QUEUED   -> NxIcon.HourglassEmpty to c.warnAccent
            AutoSyncService.ServerState.SYNCING  -> NxIcon.Sync           to c.progressAccent
            AutoSyncService.ServerState.SYNCED   -> NxIcon.Check          to c.success
            AutoSyncService.ServerState.FAILED   -> NxIcon.Close          to c.criticalAccent
            AutoSyncService.ServerState.SKIPPED  -> return@AnimatedVisibility
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(tint.copy(alpha = 0.18f))
                .border(1.dp, tint.copy(alpha = 0.55f), RoundedCornerShape(50))
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Symbol(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        }
    }
}
