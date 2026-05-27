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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewQuilt
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
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
import hivens.ui.editor.decoration.EmptySlotPlaceholder
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.LocalDragController
import hivens.ui.editor.dnd.LocalDropTargetRegistry
import hivens.ui.editor.palette.WidgetPalettePanel
import hivens.ui.widgets.home.classic.LocalHomeClassicContext
import hivens.ui.widgets.home.new.LocalHomeNewContext
import hivens.ui.widgets.library.LocalLibraryContext
import hivens.ui.widgets.shell.LocalLeftRailContext
import hivens.ui.widgets.shell.LocalRightRailContext
import hivens.widget.api.EmptySlotDecorator
import hivens.widget.api.LocalEmptySlotDecorator
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
    val availableSurfaces: List<SurfaceId> = remember(currentScreen, homeView) {
        availableSurfacesFor(currentScreen, homeView)
    }
    val controller: EditModeController = koinInject()

    var editing       by remember(availableSurfaces) { mutableStateOf(false) }
    var paletteOpen   by remember(availableSurfaces) { mutableStateOf(true) }
    var previewing    by remember(availableSurfaces) { mutableStateOf(false) }
    var selectedSurface by remember(availableSurfaces) {
        mutableStateOf(availableSurfaces.firstOrNull())
    }
    // Leaving a surface drops edit mode -- avoids a stale edit state
    // pointed at the wrong surface after navigation.
    val state: EditModeState = remember(editing, selectedSurface) {
        val sel = selectedSurface
        if (editing && sel != null) {
            EditModeState.On(sel, controller)
        } else {
            EditModeState.Off
        }
    }

    val dragController = remember { DragController() }
    val registry       = remember { DropTargetRegistry() }
    val focusManager   = LocalFocusManager.current
    val density        = LocalDensity.current

    // Chrome decorator: identity when off OR previewing, full chrome
    // only on widgets that belong to the currently-selected surface.
    // Wrong-surface widgets render plain. Previewing temporarily
    // suppresses all chrome so the user can see the real look without
    // leaving edit mode.
    val chromeDecorator: WidgetDecorator = remember(state, previewing) {
        if (state is EditModeState.On && !previewing) {
            val selected = state.surface
            decorator@{ address, index, descriptor, instance, content ->
                if (address.surface != selected) {
                    content()
                    return@decorator
                }
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
                        // Hit-test which slot received the drop. Null =
                        // pointer is off any slot; treat as cancel.
                        val targetSlot = registry.slotForPoint(committedPointer)
                            ?: return@EditableWidgetChrome
                        val targetIdx = registry.insertionIndexInSlot(targetSlot, committedPointer)
                        if (targetSlot == address) {
                            // Same slot -- reorder. -1 when moving down
                            // because removing the source shifts indices.
                            if (targetIdx != index) {
                                controller.reorderInSlot(
                                    surface   = address.surface,
                                    slot      = address.slot,
                                    fromIndex = index,
                                    toIndex   = if (targetIdx > index) targetIdx - 1 else targetIdx,
                                )
                            }
                        } else {
                            // Cross-slot move. moveWidget removes from
                            // source then inserts at target index; no
                            // index adjustment needed since the source
                            // slot is different.
                            controller.moveWidget(
                                from       = address,
                                to         = targetSlot,
                                instanceId = instance.instanceId,
                                toIndex    = targetIdx,
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

    // Empty-slot decorator: placeholder only on the selected surface
    // and only when not previewing. Other surfaces' empty slots stay
    // invisible (matches normal-mode behavior).
    val emptyDecorator: EmptySlotDecorator = remember(state, registry, previewing) {
        if (state is EditModeState.On && !previewing) {
            val selected = state.surface
            { address ->
                if (address.surface == selected) {
                    EmptySlotPlaceholder(address = address, registry = registry)
                }
            }
        } else {
            {}
        }
    }

    CompositionLocalProvider(
        LocalEditMode           provides state,
        LocalDragController     provides dragController,
        LocalDropTargetRegistry provides registry,
        LocalWidgetDecorator    provides chromeDecorator,
        LocalEmptySlotDecorator provides emptyDecorator,
        // Stub surface contexts. Surface composables that mount under
        // content() override with the real values; widgets dropped on
        // a foreign surface fall through to the stubs and render
        // with no-op callbacks instead of crashing the launcher.
        LocalHomeClassicContext provides STUB_HOME_CLASSIC,
        LocalHomeNewContext     provides STUB_HOME_NEW,
        LocalLibraryContext     provides STUB_LIBRARY,
        LocalLeftRailContext    provides STUB_LEFTRAIL,
        LocalRightRailContext   provides STUB_RIGHTRAIL,
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

            if (availableSurfaces.isNotEmpty()) {
                EditModePill(
                    active            = editing,
                    surfaces          = availableSurfaces,
                    selectedSurface   = selectedSurface,
                    onSurfacePicked   = { selectedSurface = it },
                    paletteOpen       = paletteOpen,
                    onTogglePalette   = { paletteOpen = !paletteOpen },
                    previewing        = previewing,
                    onTogglePreview   = { previewing = !previewing },
                    modifier          = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                )

                WidgetPalettePanel(
                    visible        = editing && paletteOpen && !previewing,
                    onDismiss      = { paletteOpen = false },
                    controller     = dragController,
                    registry       = registry,
                    editController = controller,
                    modifier       = Modifier.align(Alignment.TopEnd),
                )

                EditModeFab(
                    editing  = editing,
                    onToggle = {
                        editing = !editing
                        if (!editing) previewing = false
                    },
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
    surfaces: List<SurfaceId>,
    selectedSurface: SurfaceId?,
    onSurfacePicked: (SurfaceId) -> Unit,
    paletteOpen: Boolean,
    onTogglePalette: () -> Unit,
    previewing: Boolean,
    onTogglePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible  = active,
        enter    = fadeIn(spring()) + slideInVertically(spring()) { -it },
        exit     = fadeOut(spring()) + slideOutVertically(spring()) { -it },
        modifier = modifier,
    ) {
        Surface(
            color   = CelestiaTheme.colors.surface.copy(alpha = 0.94f),
            shape   = RoundedCornerShape(20.dp),
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.Tune,
                    contentDescription = null,
                    tint               = CelestiaTheme.colors.primary,
                    modifier           = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))

                // Surface picker chips. One chip per available surface;
                // the active surface has a primary tint.
                surfaces.forEach { sid ->
                    SurfaceChip(
                        surface  = sid,
                        active   = sid == selectedSurface,
                        onClick  = { onSurfacePicked(sid) },
                    )
                    Spacer(Modifier.width(4.dp))
                }

                Spacer(Modifier.width(6.dp))

                // Preview toggle -- hides chrome temporarily so the
                // user can see the real look without leaving edit
                // mode. Drag becomes impossible during preview (no
                // handles), which matches user intent.
                ToolChip(
                    icon       = if (previewing) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    label      = if (previewing) "Скрыто" else "Просмотр",
                    selected   = previewing,
                    onClick    = onTogglePreview,
                )
                Spacer(Modifier.width(4.dp))

                // Palette toggle.
                ToolChip(
                    icon     = Icons.Default.Widgets,
                    label    = if (paletteOpen) "Скрыть" else "Виджеты",
                    selected = paletteOpen,
                    onClick  = onTogglePalette,
                )
                Spacer(Modifier.width(10.dp))

                Text(
                    text  = "Esc — выйти",
                    style = MaterialTheme.typography.labelSmall,
                    color = CelestiaTheme.colors.textSecondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SurfaceChip(surface: SurfaceId, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) CelestiaTheme.colors.primary.copy(alpha = 0.18f)
             else Color.Transparent
    val fg = if (active) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary
    Surface(
        color    = bg,
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector        = surfaceIcon(surface),
                contentDescription = null,
                tint               = fg,
                modifier           = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text       = humanSurfaceShortName(surface),
                style      = MaterialTheme.typography.labelSmall,
                color      = fg,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ToolChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) CelestiaTheme.colors.primary.copy(alpha = 0.18f)
             else CelestiaTheme.colors.surfaceVariant.copy(alpha = 0.6f)
    val fg = if (selected) CelestiaTheme.colors.primary else CelestiaTheme.colors.textPrimary
    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = fg,
                modifier           = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text       = label,
                style      = MaterialTheme.typography.labelSmall,
                color      = fg,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

private fun surfaceIcon(surface: SurfaceId): androidx.compose.ui.graphics.vector.ImageVector =
    when (surface.value) {
        "appshell.leftrail"  -> Icons.Default.ViewSidebar
        "appshell.rightrail" -> Icons.Default.ViewQuilt
        else                 -> Icons.Default.Home
    }

private fun humanSurfaceShortName(surface: SurfaceId): String = when (surface.value) {
    "home.classic"        -> "Главная"
    "home.new"            -> "Главная"
    "library"             -> "Library"
    "appshell.leftrail"   -> "Лев. рейл"
    "appshell.rightrail"  -> "Прав. рейл"
    else                  -> surface.value
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

// All surfaces editable on the given screen. The first entry is the
// "main" content surface and is selected by default; the two rails
// follow. Other screens (Settings, Profile, etc.) are not widget-
// composed yet and return an empty list (FAB stays hidden).
private fun availableSurfacesFor(screen: Screen, homeView: HomeView): List<SurfaceId> {
    val main: SurfaceId = when (screen) {
        Screen.Home -> when (homeView) {
            HomeView.Classic      -> SurfaceId("home.classic")
            HomeView.LibraryFirst -> SurfaceId("library")
            HomeView.New          -> SurfaceId("home.new")
        }
        Screen.Library -> SurfaceId("library")
        else           -> return emptyList()
    }
    return listOf(
        main,
        SurfaceId("appshell.leftrail"),
        SurfaceId("appshell.rightrail"),
    )
}
