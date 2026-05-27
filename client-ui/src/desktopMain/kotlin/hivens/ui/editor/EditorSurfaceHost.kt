package hivens.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.core.data.HomeView
import hivens.ui.Screen
import hivens.ui.editor.decoration.EditableWidgetChrome
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.LocalDragController
import hivens.ui.editor.dnd.LocalDropTargetRegistry
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.widget.api.LocalWidgetDecorator
import hivens.widget.api.WidgetDecorator
import hivens.widget.model.SurfaceId
import kotlinx.coroutines.CoroutineScope
import org.koin.compose.koinInject

// EditorSurfaceHost is the single coordinator for everything edit-mode
// related on the active surface. It:
//   * resolves which SurfaceId the active Screen/HomeView maps to
//   * holds DragController + DropTargetRegistry per surface
//   * provides LocalEditMode, LocalDragController, LocalDropTargetRegistry,
//     and LocalWidgetDecorator (the decorator wraps each widget with
//     chrome only while edit mode is on)
//   * paints the FAB (edit/done morph)
//   * paints the optional "Edit layout" pill at the top
//   * paints the drag ghost following the pointer when a drag is active
//   * consumes Escape to exit edit mode
//
// editor-3 will add palette panel + cross-slot drop. editor-2 keeps
// drags within the source slot.
@Composable
fun EditorSurfaceHost(
    currentScreen: Screen,
    homeView: HomeView,
    content: @Composable () -> Unit,
) {
    val editableSurface: SurfaceId? = remember(currentScreen, homeView) {
        editableSurfaceFor(currentScreen, homeView)
    }
    val controller: EditModeController = koinInject()

    var editing by remember(editableSurface) { mutableStateOf(false) }
    // Leaving a surface drops edit mode -- avoids a stale edit state
    // pointed at the wrong surface after navigation.
    val state: EditModeState = remember(editing, editableSurface) {
        if (editing && editableSurface != null) {
            EditModeState.On(editableSurface, controller)
        } else {
            EditModeState.Off
        }
    }

    val dragController = remember { DragController() }
    val registry       = remember { DropTargetRegistry() }
    val focusManager   = LocalFocusManager.current
    val density        = LocalDensity.current

    // Chrome decorator: identity when off, full chrome when on. The
    // local is provided regardless so SlotRenderer always finds it;
    // identity is zero-cost.
    val chromeDecorator: WidgetDecorator = remember(state) {
        if (state is EditModeState.On) {
            { address, index, descriptor, instance, content ->
                EditableWidgetChrome(
                    address      = address,
                    index        = index,
                    descriptor   = descriptor,
                    instance     = instance,
                    controller   = dragController,
                    registry     = registry,
                    onRemove     = {
                        controller.removeWidget(address.surface, address.slot, instance.instanceId)
                    },
                    onCommitDrop = { committedPointer ->
                        // Compute insertion index from registered widget
                        // bounds; only commit if it actually moved.
                        val targetIdx = registry.insertionIndexInSlot(address, committedPointer)
                        // The registry counts the dragged widget too,
                        // so a no-op drop returns the source index --
                        // reorderInSlot will detect equality and short-
                        // circuit, no extra guard needed here.
                        if (targetIdx != index) {
                            controller.reorderInSlot(
                                surface   = address.surface,
                                slot      = address.slot,
                                fromIndex = index,
                                toIndex   = if (targetIdx > index) targetIdx - 1 else targetIdx,
                            )
                        }
                    },
                    content      = content,
                )
            }
        } else {
            { _, _, _, _, content -> content() }
        }
    }

    CompositionLocalProvider(
        LocalEditMode           provides state,
        LocalDragController     provides dragController,
        LocalDropTargetRegistry provides registry,
        LocalWidgetDecorator    provides chromeDecorator,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { ev ->
                    if (editing && ev.type == KeyEventType.KeyUp && ev.key == Key.Escape) {
                        editing = false
                        true
                    } else false
                },
        ) {
            // Subtle surface vignette while in edit mode -- a soft inner
            // primary tint at very low alpha to communicate "this whole
            // pane is being edited", without obscuring content.
            content()
            EditModeVignette(active = editing)

            // Drag ghost overlay -- positioned in window coords relative
            // to the host's own Box. Renders nothing when no drag is
            // active.
            DragGhostOverlay(dragController = dragController)

            if (editableSurface != null) {
                EditModePill(
                    active   = editing,
                    surface  = editableSurface,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                )

                EditModeFab(
                    editing  = editing,
                    onToggle = { editing = !editing },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                )
            }
        }
    }
}

// ── FAB ─────────────────────────────────────────────────────────────────────

@Composable
private fun EditModeFab(
    editing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalStyle.current
    val scale by animateFloatAsState(
        targetValue   = if (editing) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "fab-scale",
    )
    val container = if (editing) CelestiaTheme.colors.primary
                    else CelestiaTheme.colors.surfaceVariant
    val content   = if (editing) Color.White
                    else CelestiaTheme.colors.textPrimary

    // Subtle pulse glow while in edit mode -- communicates "live state"
    // without being noisy. Brut zeroes out animationMultiplier, so the
    // pulse goes flat there.
    val pulseTransition = rememberInfiniteTransition(label = "fab-pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = style.animationDurationMs(1800),
                easing         = LinearEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "fab-pulse-value",
    )
    val pulseAlpha = if (editing) 0.18f + pulse * 0.18f else 0f

    Box(modifier = modifier) {
        // Glow halo
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { scaleX = 1.45f; scaleY = 1.45f; alpha = pulseAlpha }
                .background(CelestiaTheme.colors.primary, shape = RoundedCornerShape(24.dp)),
        )
        FloatingActionButton(
            onClick        = onToggle,
            modifier       = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp)),
            containerColor = container,
            contentColor   = content,
            shape          = RoundedCornerShape(16.dp),
        ) {
            // Crossfade icons between Edit and Check so the morph is
            // smooth rather than a hard swap.
            Box(contentAlignment = Alignment.Center) {
                AnimatedVisibility(
                    visible = !editing,
                    enter   = fadeIn(spring()),
                    exit    = fadeOut(spring()),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit layout")
                }
                AnimatedVisibility(
                    visible = editing,
                    enter   = fadeIn(spring()),
                    exit    = fadeOut(spring()),
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Done editing")
                }
            }
        }
    }
}

// ── Top pill ────────────────────────────────────────────────────────────────

@Composable
private fun EditModePill(
    active: Boolean,
    surface: SurfaceId,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible  = active,
        enter    = fadeIn(spring()) + slideInVertically(spring()) { -it },
        exit     = fadeOut(spring()) + slideOutVertically(spring()) { -it },
        modifier = modifier,
    ) {
        Surface(
            color   = CelestiaTheme.colors.surface.copy(alpha = 0.92f),
            shape   = RoundedCornerShape(20.dp),
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.Tune,
                    contentDescription = null,
                    tint               = CelestiaTheme.colors.primary,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = "Редактирование: ${humanSurfaceName(surface)}",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = CelestiaTheme.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text  = "Esc — выйти",
                    style = MaterialTheme.typography.labelSmall,
                    color = CelestiaTheme.colors.textSecondary,
                )
            }
        }
    }
}

private fun humanSurfaceName(surface: SurfaceId): String = when (surface.value) {
    "home.classic"        -> "Главная (классика)"
    "home.new"            -> "Главная (новая)"
    "library"             -> "Library"
    "appshell.leftrail"   -> "Боковая панель"
    "appshell.rightrail"  -> "Правая панель"
    else                  -> surface.value
}

// ── Vignette ────────────────────────────────────────────────────────────────

@Composable
private fun EditModeVignette(active: Boolean) {
    val alpha by animateFloatAsState(
        targetValue   = if (active) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label         = "edit-vignette",
    )
    if (alpha <= 0.01f) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .border(
                width = 1.5.dp,
                color = CelestiaTheme.colors.primary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(0.dp),
            ),
    )
}

// ── Drag ghost ──────────────────────────────────────────────────────────────

@Composable
private fun DragGhostOverlay(dragController: DragController) {
    val active = dragController.active ?: return
    val density = LocalDensity.current
    // pointerInWindow + pickupOffset is the widget-origin in window
    // coords; the ghost should sit there. We render through graphicsLayer
    // to avoid layout invalidation when the offset changes.
    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val x = (active.pointerInWindow.x - active.pickupOffset.x)
                    val y = (active.pointerInWindow.y - active.pickupOffset.y)
                    translationX = x
                    translationY = y
                    alpha        = 0.78f
                    scaleX       = 1.04f
                    scaleY       = 1.04f
                }
                .shadow(elevation = 14.dp, shape = RoundedCornerShape(10.dp)),
        ) {
            active.ghost()
        }
    }
}

// ── Surface routing ─────────────────────────────────────────────────────────

private fun editableSurfaceFor(screen: Screen, homeView: HomeView): SurfaceId? = when (screen) {
    Screen.Home -> when (homeView) {
        HomeView.Classic      -> SurfaceId("home.classic")
        HomeView.LibraryFirst -> SurfaceId("library")
        HomeView.New          -> SurfaceId("home.new")
    }
    Screen.Library -> SurfaceId("library")
    else           -> null
}
