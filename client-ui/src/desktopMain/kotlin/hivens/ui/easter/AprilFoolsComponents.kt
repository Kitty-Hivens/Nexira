package hivens.ui.easter

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import kotlin.math.*
import kotlin.random.Random

// ─── Root wrapper ─────────────────────────────────────────────────────────────

/**
 * Wraps the entire application in the April Fools chaos system.
 *
 * Place it inside the root Box in AppRoot, wrapping AppLayout:
 *
 *   Box { CustomBackground(...); AprilFoolsWrapper(pixelCursorState, windowSize) { AppLayout(...) } }
 *
 * When [AprilFools.isActive] returns false, this composable is completely
 * transparent -- it renders [content] with zero overhead.
 *
 * @param pixelCursorState  A [State<Offset>] updated every pointer move, in window pixels.
 * @param windowSize        Current window size in pixels.
 * @param onRealClose       Called when the user finally succeeds in closing the window.
 * @param onHideTray        Called when close should minimize to tray instead.
 */
@Composable
fun AprilFoolsWrapper(
    pixelCursorState: State<Offset>,
    windowSize: IntSize,
    onRealClose: () -> Unit,
    onHideTray: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (!AprilFools.isActive()) {
        content()
        return
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        AprilFoolsEngine.run(
            scope       = scope,
            cursorState = { pixelCursorState.value },
            windowSize  = { windowSize },
        )
    }

    DisposableEffect(Unit) {
        onDispose { ChaosState.clean() }
    }

    CompositionLocalProvider(
        LocalCursorPx   provides pixelCursorState,
        LocalWindowPx   provides windowSize,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = ChaosState.shakeOffset.x
                    translationY = ChaosState.shakeOffset.y
                    rotationZ    = ChaosState.globalTiltDeg
                }
        ) {
            // Real app UI
            content()

            // Floating chaos elements rendered on top of everything
            ChaosOverlay()
        }

        // Close dialog -- rendered outside the tilted Box so it stays readable
        if (ChaosState.showCloseDialog) {
            AprilFoolsCloseDialog(
                onConfirmClose = {
                    ChaosState.showCloseDialog    = false
                    ChaosState.closeButtonEscapes = 0
                    onRealClose()
                },
                onHideTray = onHideTray?.let { fn ->
                    {
                        ChaosState.showCloseDialog    = false
                        ChaosState.closeButtonEscapes = 0
                        fn()
                    }
                },
                onStay = {
                    ChaosState.showCloseDialog = false
                },
            )
        }
    }
}

// ─── Chaos overlay ────────────────────────────────────────────────────────────

/**
 * Fullscreen transparent layer that renders all escaped buttons and ghost clones.
 * Sits at zIndex 100 so it's above everything, including dialogs launched by normal UI.
 */
@Composable
private fun ChaosOverlay() {
    Box(Modifier.fillMaxSize().zIndex(100f)) {
        ChaosState.buttons.forEach { btn ->
            if (btn.isEscaped()) {
                key(btn.id) { EscapedButtonRenderer(btn, isGhost = false) }
            }
        }
        ChaosState.ghosts.forEach { ghost ->
            key(ghost.id) { EscapedButtonRenderer(ghost, isGhost = true) }
        }
    }
}

// ─── Escaped button renderer ──────────────────────────────────────────────────

/**
 * Renders a single escaped (or ghost) button in the overlay.
 * Reads animated properties from [FloatingButton] -- recomposes automatically
 * whenever those snapshot-state values change.
 */
@Composable
private fun EscapedButtonRenderer(btn: FloatingButton, isGhost: Boolean) {
    val x     = btn.overlayX.value
    val y     = btn.overlayY.value
    val rot   = btn.overlayRot.value
    val scale = btn.overlayScale.value
    val alpha = btn.overlayAlpha.value

    Box(
        Modifier
            .offset { IntOffset(x.toInt(), y.toInt()) }
            .graphicsLayer {
                rotationZ    = rot
                scaleX       = scale
                scaleY       = scale
                this.alpha   = alpha
            }
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // ── The button ────────────────────────────────────────────────────
            Button(
                onClick = { if (!isGhost) btn.onClick() },
                modifier = Modifier
                    .width(btn.widthPx.dp)
                    .height(btn.heightPx.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGhost)
                        CelestiaTheme.colors.primary.copy(alpha = 0.38f)
                    else
                        CelestiaTheme.colors.primary,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (isGhost) 0.dp else 4.dp
                ),
            ) {
                Text(
                    text       = btn.label,
                    color      = if (isGhost) Color.White.copy(alpha = 0.5f) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 13.sp,
                )
            }

            // ── Walking legs (only when LEGS_WALKING phase) ───────────────────
            if (btn.hasLegs && !isGhost) {
                LegsCanvas(widthPx = btn.widthPx, cycle = btn.legCycle)
            }
        }
    }
}

// ─── Pixel stick-legs ─────────────────────────────────────────────────────────

/**
 * Draws two animated stick legs beneath a button using [Canvas].
 * Each leg is a line + a horizontal "foot" at the bottom.
 * The legs alternate between forward and backward step using a sine wave on [cycle].
 *
 * @param widthPx Button width in layout pixels (used to position legs under button).
 * @param cycle   Walking cycle 0..1 -- updated by the engine each frame.
 */
@Composable
private fun LegsCanvas(widthPx: Float, cycle: Float) {
    val legColor  = CelestiaTheme.colors.textPrimary.copy(alpha = 0.9f)
    Canvas(
        modifier = Modifier
            .width(widthPx.dp)
            .height(26.dp)
    ) {
        val strokeW   = 2.8f
        val topY      = 0f
        val botY      = size.height * 0.72f
        val footLen   = size.width * 0.13f
        val maxSwing  = 18f   // px horizontal swing at the foot

        // Left leg -- phase 0
        val leftRootX = size.width * 0.32f
        val leftFootX = leftRootX + sin(cycle * 2f * PI.toFloat()) * maxSwing
        drawLine(legColor, Offset(leftRootX, topY),  Offset(leftFootX, botY), strokeWidth = strokeW)
        drawLine(legColor, Offset(leftFootX, botY),   Offset(leftFootX + footLen, botY), strokeWidth = strokeW)

        // Right leg -- 180° out of phase
        val rightRootX = size.width * 0.68f
        val rightFootX = rightRootX + sin((cycle + 0.5f) * 2f * PI.toFloat()) * maxSwing
        drawLine(legColor, Offset(rightRootX, topY), Offset(rightFootX, botY), strokeWidth = strokeW)
        drawLine(legColor, Offset(rightFootX, botY),  Offset(rightFootX + footLen, botY), strokeWidth = strokeW)
    }
}

// ─── AprilFoolsButton ─────────────────────────────────────────────────────────

/**
 * Drop-in replacement for any Button that should participate in chaos.
 *
 * Behavior:
 *  - Registers itself with [ChaosState] so the engine can target it.
 *  - Tracks its own position via [onGloballyPositioned].
 *  - Becomes invisible (alpha=0) when the engine takes control.
 *  - Handles local FLEEING phase by offsetting itself on cursor hover.
 *  - Falls back to a normal Button when April Fools is not active.
 *
 * @param id     Stable unique string -- must not change across recompositions.
 * @param text   Localized label shown on the button.
 */
@Composable
fun AprilFoolsButton(
    id: String,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = CelestiaTheme.colors.primary,
    ),
) {
    // ── Pass-through when chaos is not active ──────────────────────────────
    if (!AprilFools.isActive()) {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, colors = colors) {
            Text(text)
        }
        return
    }

    // ── Resolve or create the FloatingButton record ───────────────────────
    val btn = remember(id) {
        ChaosState.find(id) ?: FloatingButton(
            id       = id,
            label    = text,
            widthPx  = 160f,
            heightPx = 50f,
            onClick  = onClick,
        ).also { ChaosState.register(it) }
    }

    LaunchedEffect(onClick) { btn.onClick = onClick }

    // Keep label in sync (locales can change at runtime)
    DisposableEffect(id) {
        onDispose { ChaosState.unregister(id) }
    }

    // ── Local flee offset (FLEEING phase only) ────────────────────────────
    var fleeX by remember { mutableStateOf(0f) }
    var fleeY by remember { mutableStateOf(0f) }
    val animFleeX by animateFloatAsState(fleeX, spring(stiffness = 450f, dampingRatio = 0.65f), label = "fleeX")
    val animFleeY by animateFloatAsState(fleeY, spring(stiffness = 450f, dampingRatio = 0.65f), label = "fleeY")

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    LaunchedEffect(isHovered) {
        if (isHovered && btn.phase == ChaosPhase.FLEEING) {
            // Jump to a random position relative to current spot
            fleeX = Random.nextFloat() * 180f - 90f
            fleeY = Random.nextFloat() * 80f  - 40f
        }
    }

    // ── Render ────────────────────────────────────────────────────────────
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .offset { IntOffset(animFleeX.toInt(), animFleeY.toInt()) }
            .hoverable(interactionSource)
            .onGloballyPositioned { coords ->
                // Update origin so the engine knows where to snap the overlay clone
                btn.originPx = coords.positionInWindow()
            }
            .graphicsLayer {
                // Invisible while engine has control; animates back when returning
                alpha = if (btn.originalVisible) 1f else 0f
            }
            .then(
                if (!btn.originalVisible) {
                    Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent(PointerEventPass.Initial)
                                    .changes.forEach { it.consume() }
                            }
                        }
                    }
                } else Modifier
            ),
        colors            = colors,
        interactionSource = interactionSource,
    ) {
        Text(text)
    }
}

// ─── Close dialog ─────────────────────────────────────────────────────────────

/**
 * Replaces the standard window close action with a torturous confirmation dialog.
 *
 * The "Close" button runs from the cursor. After [surrenderAfter] escapes it
 * stops running and lets the user click it. The "Stay" button always works immediately.
 * There is no way to dismiss this dialog by clicking outside it.
 *
 * @param onConfirmClose  Called when the user finally closes (real exit).
 * @param onHideTray      If non-null, shows a third option to hide to tray.
 * @param onStay          Called when the user clicks "Stay".
 * @param surrenderAfter  How many escapes before the close button gives up (default 8).
 */
@Composable
fun AprilFoolsCloseDialog(
    onConfirmClose: () -> Unit,
    onHideTray: (() -> Unit)? = null,
    onStay: () -> Unit,
    surrenderAfter: Int = 8,
) {
    val s         = LocalStrings.current
    val escapes   = ChaosState.closeButtonEscapes
    val surrendered = escapes >= surrenderAfter

    var closeOffX by remember { mutableStateOf(0f) }
    var closeOffY by remember { mutableStateOf(0f) }
    val animCloseX by animateFloatAsState(closeOffX, spring(stiffness = 520f, dampingRatio = 0.6f), label = "cdx")
    val animCloseY by animateFloatAsState(closeOffY, spring(stiffness = 520f, dampingRatio = 0.6f), label = "cdy")

    val closeBtnInteraction = remember { MutableInteractionSource() }
    val isCloseHovered by closeBtnInteraction.collectIsHoveredAsState()

    LaunchedEffect(isCloseHovered) {
        if (isCloseHovered && !surrendered) {
            ChaosState.closeButtonEscapes++
            closeOffX = Random.nextFloat() * 220f - 110f
            closeOffY = Random.nextFloat() * 110f - 55f
        }
    }

    // Non-dismissible dialog -- clicking outside does nothing
    Dialog(onDismissRequest = { /* intentionally empty */ }) {
        Surface(
            modifier       = Modifier.width(440.dp).wrapContentHeight(),
            shape          = MaterialTheme.shapes.large,
            color          = CelestiaTheme.colors.surface,
            tonalElevation = 10.dp,
        ) {
            Column(
                modifier            = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {

                // Title changes as escape count grows
                Text(
                    text       = s.aprilCloseTitle(escapes),
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color      = CelestiaTheme.colors.textPrimary,
                    textAlign  = TextAlign.Center,
                )

                Text(
                    text  = s.aprilCloseBody(escapes),
                    color = CelestiaTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(Modifier.height(16.dp))

                // Button row -- "Close" can escape, "Stay" cannot
                Box(
                    modifier          = Modifier.fillMaxWidth().height(80.dp),
                    contentAlignment  = Alignment.Center,
                ) {
                    // Stay (left side, always reachable)
                    OutlinedButton(
                        onClick  = onStay,
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) { Text(s.aprilCloseStay) }

                    // Hide to tray (middle, optional)
                    if (onHideTray != null) {
                        TextButton(
                            onClick  = onHideTray,
                            modifier = Modifier.align(Alignment.Center),
                        ) { Text(s.aprilCloseHideTray) }
                    }

                    // Close (right side, runs away)
                    Button(
                        onClick  = { if (surrendered) onConfirmClose() },
                        enabled  = surrendered,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .offset { IntOffset(animCloseX.toInt(), animCloseY.toInt()) }
                            .hoverable(closeBtnInteraction),
                        colors = ButtonDefaults.buttonColors(
                            containerColor         = CelestiaTheme.colors.error,
                            disabledContainerColor = CelestiaTheme.colors.error.copy(alpha = 0.75f),
                        ),
                        interactionSource = closeBtnInteraction,
                    ) {
                        Text(
                            text  = if (surrendered) s.aprilCloseSurrender else s.aprilCloseClose,
                            color = Color.White,
                        )
                    }
                }

                // Escape counter hint
                if (escapes > 0 && !surrendered) {
                    Text(
                        text  = s.aprilCloseEscapeCount(escapes, surrenderAfter),
                        style = MaterialTheme.typography.labelSmall,
                        color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.45f),
                    )
                }
            }
        }
    }
}
