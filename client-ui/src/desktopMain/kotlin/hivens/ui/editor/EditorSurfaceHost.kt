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
import androidx.compose.material.icons.automirrored.filled.ViewQuilt
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hivens.core.data.HomeView
import hivens.core.data.UiStyle
import hivens.launcher.LayoutGraphRepository
import hivens.ui.Screen
import hivens.ui.customization.CustomizationSettings
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.widget.api.LocalLayoutGraph
import kotlinx.coroutines.CoroutineScope as KotlinCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import hivens.ui.editor.decoration.EditableWidgetChrome
import hivens.ui.editor.decoration.EmptySlotPlaceholder
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.LocalDragController
import hivens.ui.editor.dnd.LocalDropTargetRegistry
import hivens.ui.editor.palette.WidgetPalettePanel
import hivens.ui.editor.props.WidgetPropPanel
import hivens.ui.editor.presets.PresetEnvelope
import hivens.ui.editor.presets.PresetManagerPanel
import hivens.ui.editor.presets.PresetMeta
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.widgets.home.classic.LocalHomeClassicContext
import hivens.ui.widgets.home.new.LocalHomeNewContext
import hivens.ui.widgets.library.LocalLibraryContext
import hivens.ui.widgets.shell.LocalLeftRailContext
import hivens.ui.widgets.shell.LocalRightRailContext
import hivens.ui.widgets.about.LocalAboutContext
import hivens.ui.widgets.about.STUB_ABOUT
import hivens.ui.widgets.bgsettings.LocalBgSettingsContext
import hivens.ui.widgets.bgsettings.STUB_BG_SETTINGS
import hivens.ui.widgets.customization.LocalCustomizationContext
import hivens.ui.widgets.customization.STUB_CUSTOMIZATION
import hivens.ui.widgets.profile.LocalProfileContext
import hivens.ui.widgets.profile.STUB_PROFILE
import hivens.ui.widgets.serverdetails.LocalServerDetailsContext
import hivens.ui.widgets.serverdetails.STUB_SERVER_DETAILS
import hivens.ui.widgets.themepicker.LocalThemePickerContext
import hivens.ui.widgets.themepicker.STUB_THEME_PICKER
import hivens.widget.api.EmptySlotDecorator
import hivens.widget.api.LocalEmptySlotDecorator
import hivens.widget.api.LocalSlotControlDecorator
import hivens.widget.api.LocalSlotDividerDecorator
import hivens.widget.api.LocalSlotPath
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.widget.api.LocalWidgetDecorator
import hivens.widget.api.SlotControlDecorator
import hivens.widget.api.SlotDividerDecorator
import hivens.widget.api.WidgetDecorator
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.traverse
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

// Which widget instance the prop panel is currently editing.
private data class PropTarget(val path: SlotPath, val instanceId: String)

@Composable
fun EditorSurfaceHost(
    currentScreen: Screen,
    homeView: HomeView,
    customization: CustomizationSettings = CustomizationSettings(),
    onCustomizationChanged: (CustomizationSettings) -> Unit = {},
    uiStyle: UiStyle = UiStyle.Celestia,
    onUiStyleChanged: (UiStyle) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val availableSurfaces: List<SurfaceId> = remember(currentScreen, homeView) {
        availableSurfacesFor(currentScreen, homeView)
    }
    val controller: EditModeController = koinInject()
    val layoutRepo: LayoutGraphRepository = koinInject()
    val presetRepo: PresetRepository      = koinInject()
    val coroutineScope = rememberCoroutineScope()
    val s = LocalStrings.current

    var editing       by remember(availableSurfaces) { mutableStateOf(false) }
    var paletteOpen   by remember(availableSurfaces) { mutableStateOf(true) }
    var previewing    by remember(availableSurfaces) { mutableStateOf(false) }
    var presetPanelOpen by remember(availableSurfaces) { mutableStateOf(false) }
    var resetSurfaceConfirm by remember(availableSurfaces) { mutableStateOf(false) }
    var selectedSurface by remember(availableSurfaces) {
        mutableStateOf(availableSurfaces.firstOrNull())
    }
    // Prop editor target. Cleared on surface change (keyed remember), on
    // dismiss, and on leaving edit mode; while set, the palette hides so
    // the two right-edge panels do not overlap.
    var propTarget by remember(availableSurfaces) { mutableStateOf<PropTarget?>(null) }
    // Any edit-mode exit (FAB / Escape / Ctrl+E) drops the prop target, so
    // re-entering does not silently reopen the last panel with the palette
    // still hidden.
    LaunchedEffect(editing) { if (!editing) propTarget = null }
    val currentGraph = LocalLayoutGraph.current
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

    // Window-level Ctrl+E (AppShell onPreviewKeyEvent) bumps
    // controller.editToggleSignal; this observer flips local edit
    // state. `seen` initialises to the current tick before collecting
    // so navigating into a fresh host (new remember, but the singleton
    // tick may already be > 0) does not spuriously toggle on mount.
    // Only editable surfaces react; leaving edit mode drops preview,
    // matching the FAB + Escape paths.
    //
    // The signal sits on the singleton controller, so every mounted
    // host observes it. Safe because AppLayout mounts exactly one host
    // (its Crossfade swaps screen content *inside* the host, not the
    // host itself) -- there is never a second, hidden host to flip into
    // edit mode behind the user's back.
    LaunchedEffect(availableSurfaces) {
        var seen = controller.editToggleSignal.value
        snapshotFlow { controller.editToggleSignal.value }.collect { tick ->
            if (tick != seen) {
                seen = tick
                if (availableSurfaces.isNotEmpty()) {
                    editing = !editing
                    if (!editing) previewing = false
                }
            }
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
    //
    // The decorator reads LocalSlotPath inside the @Composable lambda
    // body (the lambda runs in composition because it's invoked from
    // SlotRenderer's render path). The path identifies the full nested
    // address of the widget being rendered; chrome uses it both as the
    // registry key for drop-target bounds and to compose into nested
    // slots correctly.
    val chromeDecorator: WidgetDecorator = remember(state, previewing) {
        if (state is EditModeState.On && !previewing) {
            val selected = state.surface
            decorator@{ address, index, descriptor, instance, content ->
                if (address.surface != selected) {
                    content()
                    return@decorator
                }
                val path = LocalSlotPath.current
                val graph = LocalLayoutGraph.current
                val orientation = graph.traverse(path)?.orientation ?: SlotOrientation.Column
                EditableWidgetChrome(
                    path         = path,
                    index        = index,
                    descriptor   = descriptor,
                    instance     = instance,
                    controller   = dragController,
                    registry     = registry,
                    orientation  = orientation,
                    onRemove     = {
                        controller.removeWidget(path, instance.instanceId)
                    },
                    onEditProps  = { propTarget = PropTarget(path, instance.instanceId) },
                    onCommitDrop = { committedPointer ->
                        // Hit-test which slot received the drop. Null =
                        // pointer is off any slot; treat as cancel.
                        val targetPath = registry.slotForPoint(committedPointer)
                            ?: return@EditableWidgetChrome
                        val targetOrientation = graph.traverse(targetPath)?.orientation
                            ?: SlotOrientation.Column
                        val targetIdx = registry.insertionIndexInSlot(targetPath, committedPointer, targetOrientation)
                        if (targetPath == path) {
                            // Same slot -- reorder. -1 when moving down
                            // because removing the source shifts indices.
                            if (targetIdx != index) {
                                controller.reorderInSlot(
                                    path      = path,
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
                                from       = path,
                                to         = targetPath,
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

    // Empty-slot decorator: placeholder on every editable surface
    // while edit mode is on. Reads LocalSlotPath so a nested empty
    // slot (container with no children) registers its bounds against
    // the nested path key rather than colliding with another container
    // at the same (surface, slot) leaf coords.
    val emptyDecorator: EmptySlotDecorator = remember(state, registry, previewing) {
        if (state is EditModeState.On && !previewing) {
            { _ ->
                val path = LocalSlotPath.current
                EmptySlotPlaceholder(path = path, registry = registry)
            }
        } else {
            {}
        }
    }

    // Slot control decorator: a compact orientation + grid-columns
    // control at the start of every non-empty slot on the selected
    // surface. Identity (renders nothing) when off, previewing, or for a
    // foreign surface -- so other surfaces and production builds pay
    // nothing. SlotRenderer invokes this as the slot's first child.
    val slotControlDecorator: SlotControlDecorator = remember(state, previewing) {
        if (state is EditModeState.On && !previewing) {
            val selected = state.surface
            { path, content ->
                if (path.surface == selected) {
                    SlotControl(path = path, content = content, controller = controller)
                }
            }
        } else {
            { _, _ -> }
        }
    }

    // Slot divider decorator: a draggable handle between adjacent widgets
    // in a Row/Column slot on the selected surface, redistributing their
    // weight. Identity off / previewing / foreign surface.
    val slotDividerDecorator: SlotDividerDecorator = remember(state, previewing) {
        if (state is EditModeState.On && !previewing) {
            val selected = state.surface
            { path, content, leftIndex ->
                if (path.surface == selected) {
                    SlotDivider(
                        path       = path,
                        content    = content,
                        leftIndex  = leftIndex,
                        controller = controller,
                        registry   = registry,
                    )
                }
            }
        } else {
            { _, _, _ -> }
        }
    }

    CompositionLocalProvider(
        LocalEditMode           provides state,
        LocalDragController     provides dragController,
        LocalDropTargetRegistry provides registry,
        LocalWidgetDecorator    provides chromeDecorator,
        LocalEmptySlotDecorator provides emptyDecorator,
        LocalSlotControlDecorator provides slotControlDecorator,
        LocalSlotDividerDecorator provides slotDividerDecorator,
        // Stub surface contexts. Surface composables that mount under
        // content() override with the real values; widgets dropped on
        // a foreign surface fall through to the stubs and render
        // with no-op callbacks instead of crashing the launcher.
        LocalHomeClassicContext provides STUB_HOME_CLASSIC,
        LocalHomeNewContext     provides STUB_HOME_NEW,
        LocalLibraryContext     provides STUB_LIBRARY,
        LocalLeftRailContext    provides STUB_LEFTRAIL,
        LocalRightRailContext     provides STUB_RIGHTRAIL,
        LocalAboutContext         provides STUB_ABOUT,
        LocalBgSettingsContext    provides STUB_BG_SETTINGS,
        LocalCustomizationContext provides STUB_CUSTOMIZATION,
        LocalProfileContext       provides STUB_PROFILE,
        LocalServerDetailsContext provides STUB_SERVER_DETAILS,
        LocalThemePickerContext   provides STUB_THEME_PICKER,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { ev ->
                    // Escape exits edit mode. Ctrl+E entry/toggle is
                    // handled at Window scope (see AppShell) so it works
                    // regardless of which descendant holds focus -- a
                    // Box-level handler misses the chord when the side
                    // rails own focus.
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
                    onOpenPresets     = { presetPanelOpen = true },
                    onRequestReset    = { if (selectedSurface != null) resetSurfaceConfirm = true },
                    modifier          = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                )

                val surfaceForReset = selectedSurface
                if (resetSurfaceConfirm && surfaceForReset != null) {
                    AlertDialog(
                        onDismissRequest = { resetSurfaceConfirm = false },
                        title            = { Text(s.editorResetSurfaceTitle) },
                        text             = {
                            Text(
                                text = s.editorResetSurfaceBody(humanSurfaceName(surfaceForReset, s)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                controller.resetSurface(surfaceForReset)
                                resetSurfaceConfirm = false
                            }) { Text(s.editorReset, color = CelestiaTheme.colors.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { resetSurfaceConfirm = false }) { Text(s.editorCancel) }
                        },
                    )
                }

                PresetManagerPanel(
                    visible       = editing && presetPanelOpen,
                    onDismiss     = { presetPanelOpen = false },
                    onSaveCurrent = { name ->
                        val envelope = PresetEnvelope(
                            name          = name,
                            createdAt     = System.currentTimeMillis(),
                            graph         = currentGraph,
                            customization = customization,
                            uiStyle       = uiStyle,
                        )
                        coroutineScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                presetRepo.save(envelope)
                            }
                        }
                    },
                    onLoad = { meta ->
                        coroutineScope.launch {
                            val env = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                presetRepo.load(meta.name)
                            } ?: return@launch
                            layoutRepo.update { env.graph }
                            onCustomizationChanged(env.customization)
                            onUiStyleChanged(env.uiStyle)
                            presetPanelOpen = false
                        }
                    },
                    onDelete = { meta ->
                        coroutineScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                presetRepo.delete(meta.name)
                            }
                        }
                    },
                    onExport = { meta ->
                        // Open the parent dir in the OS file manager and
                        // let the user copy from there. Avoids depending
                        // on a save-file dialog API that the current
                        // FileKit version does not expose.
                        coroutineScope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching {
                                    java.awt.Desktop.getDesktop().open(meta.sourcePath.parent.toFile())
                                }
                            }
                        }
                    },
                    listProvider = { presetRepo.list() },
                )

                WidgetPalettePanel(
                    visible        = editing && paletteOpen && !previewing && propTarget == null,
                    onDismiss      = { paletteOpen = false },
                    controller     = dragController,
                    registry       = registry,
                    editController = controller,
                    modifier       = Modifier.align(Alignment.TopEnd),
                )

                WidgetPropPanel(
                    visible    = editing && !previewing && propTarget != null,
                    path       = propTarget?.path,
                    instanceId = propTarget?.instanceId,
                    controller = controller,
                    onDismiss  = { propTarget = null },
                    modifier   = Modifier.align(Alignment.TopEnd),
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
    val s = LocalStrings.current
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
                    Icon(Icons.Default.Edit, contentDescription = s.editorFabEdit)
                }
                AnimatedVisibility(
                    visible = editing,
                    enter   = fadeIn(spring()),
                    exit    = fadeOut(spring()),
                ) {
                    Icon(Icons.Default.Check, contentDescription = s.editorFabDone)
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
    onOpenPresets: () -> Unit,
    onRequestReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
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
                    label      = if (previewing) s.editorPreviewHidden else s.editorPreview,
                    selected   = previewing,
                    onClick    = onTogglePreview,
                )
                Spacer(Modifier.width(4.dp))

                // Palette toggle.
                ToolChip(
                    icon     = Icons.Default.Widgets,
                    label    = if (paletteOpen) s.editorPaletteToggleHide else s.editorWidgets,
                    selected = paletteOpen,
                    onClick  = onTogglePalette,
                )
                Spacer(Modifier.width(4.dp))

                // Presets dialog.
                ToolChip(
                    icon     = Icons.Default.Inventory2,
                    label    = s.editorPresetsTitle,
                    selected = false,
                    onClick  = onOpenPresets,
                )
                Spacer(Modifier.width(4.dp))

                // Escape hatch: reset the currently selected surface
                // to its bundled default. Destructive tint signals it
                // is a different class of action from the neutral
                // toggles next to it; confirmation dialog handled at
                // host level. Disabled when no surface is selected so
                // the chip cannot pretend to be live.
                ToolChip(
                    icon        = Icons.Default.RestartAlt,
                    label       = s.editorReset,
                    selected    = false,
                    onClick     = onRequestReset,
                    destructive = true,
                    enabled     = selectedSurface != null,
                )
                Spacer(Modifier.width(10.dp))

                Text(
                    text  = s.editorEscHint,
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
    val s = LocalStrings.current
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
                text       = humanSurfaceShortName(surface, s),
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
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = when {
        !enabled    -> CelestiaTheme.colors.surfaceVariant.copy(alpha = 0.3f)
        destructive -> CelestiaTheme.colors.error.copy(alpha = 0.12f)
        selected    -> CelestiaTheme.colors.primary.copy(alpha = 0.18f)
        else        -> CelestiaTheme.colors.surfaceVariant.copy(alpha = 0.6f)
    }
    val fg = when {
        !enabled    -> CelestiaTheme.colors.textSecondary.copy(alpha = 0.45f)
        destructive -> CelestiaTheme.colors.error
        selected    -> CelestiaTheme.colors.primary
        else        -> CelestiaTheme.colors.textPrimary
    }
    Surface(color = bg, shape = RoundedCornerShape(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(enabled = enabled) { onClick() }
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
                fontWeight = if (selected || destructive) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

private fun surfaceIcon(surface: SurfaceId): androidx.compose.ui.graphics.vector.ImageVector =
    when (surface.value) {
        "appshell.leftrail"  -> Icons.AutoMirrored.Filled.ViewSidebar
        "appshell.rightrail" -> Icons.AutoMirrored.Filled.ViewQuilt
        else                 -> Icons.Default.Home
    }

private fun humanSurfaceShortName(surface: SurfaceId, s: AppStrings): String = when (surface.value) {
    "home.classic"        -> s.editorSurfShortHome
    "home.new"            -> s.editorSurfShortHome
    "library"             -> s.editorSurfShortLibrary
    "appshell.leftrail"   -> s.editorSurfShortLeftRail
    "appshell.rightrail"  -> s.editorSurfShortRightRail
    "about"               -> s.editorSurfShortAbout
    "bg.settings"         -> s.editorSurfShortBg
    "customization"       -> s.editorSurfShortStyle
    "profile"             -> s.editorSurfShortProfile
    "server.details"      -> s.editorSurfShortServer
    "theme.picker"        -> s.editorSurfShortTheme
    else                  -> surface.value
}

private fun humanSurfaceName(surface: SurfaceId, s: AppStrings): String = when (surface.value) {
    "home.classic"        -> s.editorSurfHomeClassic
    "home.new"            -> s.editorSurfHomeNew
    "library"             -> s.editorSurfLibrary
    "appshell.leftrail"   -> s.editorSurfLeftRail
    "appshell.rightrail"  -> s.editorSurfRightRail
    "about"               -> s.editorSurfAbout
    "bg.settings"         -> s.editorSurfBg
    "customization"       -> s.editorSurfStyle
    "profile"             -> s.editorSurfProfile
    "server.details"      -> s.editorSurfServer
    "theme.picker"        -> s.editorSurfTheme
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
    // The OS cursor is hidden for the duration of the drag by attaching
    // a transparent AWT cursor as the hover icon of this fullscreen
    // overlay. pointerHoverIcon does not intercept pointer events, so
    // the underlying drag handler keeps receiving updates.
    val transparentCursor = remember { transparentPointerIcon() }
    // graphicsLayer.translationX/Y translates relative to the host
    // Box's natural untranslated position, NOT window (0, 0). When the
    // EditorSurfaceHost mounts inside AppLayout after the left rail
    // (~64dp wide), the host's local origin is at window (railWidth,
    // topbarHeight) -- applying pointer-in-window coords directly as
    // translation drags the ghost away from the cursor by exactly that
    // offset. Track the overlay's own window origin and subtract it so
    // the ghost lands at the real pointer position.
    var overlayOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerHoverIcon(icon = transparentCursor, overrideDescendants = true)
            .onGloballyPositioned { coords ->
                overlayOriginInWindow = coords.boundsInWindow().topLeft
            },
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val targetWindowX = active.pointerInWindow.x - active.pickupOffset.x
                    val targetWindowY = active.pointerInWindow.y - active.pickupOffset.y
                    translationX = targetWindowX - overlayOriginInWindow.x
                    translationY = targetWindowY - overlayOriginInWindow.y
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

private fun transparentPointerIcon(): PointerIcon {
    val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val cursor = java.awt.Toolkit.getDefaultToolkit()
        .createCustomCursor(image, java.awt.Point(0, 0), "drag-ghost")
    return PointerIcon(cursor)
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
        Screen.About                  -> SurfaceId("about")
        Screen.BackgroundSettings     -> SurfaceId("bg.settings")
        Screen.CustomizationExtension -> SurfaceId("customization")
        Screen.Library                -> SurfaceId("library")
        Screen.Profile                -> SurfaceId("profile")
        is Screen.ServerDetails       -> SurfaceId("server.details")
        Screen.ThemePicker            -> SurfaceId("theme.picker")
        // Other widget-composed surfaces from B.1 land here as the
        // rest of the screens migrate over.
        else               -> return emptyList()
    }
    return listOf(
        main,
        SurfaceId("appshell.leftrail"),
        SurfaceId("appshell.rightrail"),
    )
}
