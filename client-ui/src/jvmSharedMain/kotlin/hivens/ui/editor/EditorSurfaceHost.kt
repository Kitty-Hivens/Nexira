package hivens.ui.editor

import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import hivens.ui.platform.SystemActions
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.editor.presets.PresetManagerPanel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.config.Branding
import hivens.ui.customization.CustomizationSettings
import hivens.ui.editor.decoration.EditableWidgetChrome
import hivens.ui.editor.decoration.EmptySlotPlaceholder
import hivens.ui.editor.decoration.UnsupportedWidgetPlaceholder
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.editor.dnd.EditFocus
import hivens.ui.editor.dnd.LocalDragController
import hivens.ui.editor.dnd.LocalDropTargetRegistry
import hivens.ui.editor.dnd.LocalEditFocus
import hivens.ui.editor.dnd.WidgetKey
import hivens.ui.editor.palette.WidgetPalettePanel
import hivens.ui.editor.props.ScreenPropertiesPanel
import hivens.ui.editor.props.ScreenTarget
import hivens.ui.editor.props.SurfacePropertiesPanel
import hivens.ui.editor.props.WidgetModulesPanel
import hivens.ui.editor.props.WidgetPropPanel
import hivens.ui.i18n.nexiraStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.layerTrigger
import hivens.ui.layout.DocumentStore
import hivens.ui.notifications.Kind
import hivens.ui.notifications.NotifAction
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.Severity
import hivens.ui.nx.NxAlertDialog
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuItem
import hivens.ui.press.TextButton
import hivens.ui.press.clickable
import hivens.ui.screens.custom.ScreenIcons
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status
import hivens.ui.widgets.HiddenWidget
import hivens.ui.widgets.modules.WidgetModules
import hivens.widget.api.EmptySlotDecorator
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalEmptySlotDecorator
import hivens.widget.api.LocalHiddenWidget
import hivens.widget.api.LocalMapPanOnPrimary
import hivens.widget.api.LocalPlacementReflow
import hivens.widget.api.LocalRefusedMount
import hivens.widget.api.LocalSlotBoundsReporter
import hivens.widget.api.LocalSlotBoundsWithdrawal
import hivens.widget.api.LocalSlotChromeModifier
import hivens.widget.api.LocalSlotMotionMs
import hivens.widget.api.LocalUnknownWidgetDecorator
import hivens.widget.api.LocalWidgetDecorator
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.SlotBoundsReporter
import hivens.widget.api.SlotChromeModifier
import hivens.widget.api.UnknownWidgetDecorator
import hivens.widget.api.WidgetDecorator
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.Document
import hivens.widget.model.FlowSpec
import hivens.widget.model.NodePath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt
import hivens.widget.model.screen
import hivens.widget.model.slotPath
import org.koin.compose.koinInject
import org.slf4j.LoggerFactory

// The editor over one composition's window. Everything drawn from the document is arranged at once: every widget
// wears its chrome while it is the one the pointer is over, every slot can be selected and dropped into, and the frame
// is arranged where it stands. There are no tabs to pick a surface by, because there are no surfaces.
//
// It provides LocalEditMode and the editor's hooks into the kernel (the widget, empty-slot and unknown-widget
// decorators, the slot chrome, the bounds reports) only while somebody is arranging and not previewing, and the
// kernel's defaults otherwise. It paints the pill, the docked panels, the drag ghost and the vignette, asks for the
// person's word before the first edit of the standard document, and backs out one step at a time on Escape and back
// (see EditorBackOut). Ctrl+E is claimed at window level and handed over through [ShellSignals].

/** What the panel at the right edge shows: one thing at a time, so two panels never fight for the corner. */
private sealed interface Dock {
    data object Palette : Dock
    data class Props(val key: WidgetKey) : Dock
    data object Screen : Dock
    /** The left rail's selection style, a setting of the person's, opened from the rail's own panel and going back to it. */
    data class RailStyle(val back: Dock) : Dock
    data object Nothing : Dock
}

private val log = LoggerFactory.getLogger("EditorSurfaceHost")

/** The left rail region, whose panel opens the rail's selection style. */
private val LEFT_RAIL = WidgetKind("appshell.region.left")

@Composable
fun EditorSurfaceHost(
    composition: CompositionName,
    config: ConfigId,
    customization: CustomizationSettings = CustomizationSettings(),
    onCustomizationChanged: (CustomizationSettings) -> Unit = {},
    /** Opens a config, the way the rail does. The editor uses it to show a screen it just made. */
    onOpen: (ConfigId) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val editor: DocumentEditor = koinInject()
    val signals: ShellSignals = koinInject()
    val store: DocumentStore = koinInject()
    val standard: Document = koinInject()
    val widgetModules: WidgetModules = koinInject()
    val notifications: NotificationCenter = koinInject()
    val document by store.document.collectAsState()
    val widgetRegistry = LocalWidgetRegistry.current
    val pagesOf = remember(widgetRegistry) { { kind: WidgetKind -> widgetRegistry[kind]?.pages.orEmpty() } }
    val s = nexiraStrings()

    val chromeBounds = remember { ShellChromeBounds() }
    val hostRect = remember { mutableStateOf(Rect.Zero) }

    var editing by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var paletteOpen by remember { mutableStateOf(true) }
    var dock by remember { mutableStateOf<Dock>(Dock.Palette) }
    var entryMenuAt by remember { mutableStateOf<Offset?>(null) }
    var modulesPanelOpen by remember { mutableStateOf(false) }
    var presetsOpen by remember { mutableStateOf(false) }
    val presetRepo: PresetRepository = koinInject()
    val presetScope = rememberCoroutineScope()
    var resetScreenConfirm by remember { mutableStateOf(false) }
    var resetAllConfirm by remember { mutableStateOf(false) }
    var standardConfirm by remember { mutableStateOf(false) }
    var resetMenuOpen by remember { mutableStateOf(false) }

    val selectedSlotState = remember { mutableStateOf<NodePath?>(null) }
    var selectedSlotRect by remember { mutableStateOf<Rect?>(null) }
    var slotMenuCursor by remember { mutableStateOf<Offset?>(null) }
    var handleMenuOpen by remember { mutableStateOf(false) }
    fun clearSlotSelection() {
        selectedSlotState.value = null
        selectedSlotRect = null
        slotMenuCursor = null
        handleMenuOpen = false
    }

    val arranging = editing && !previewing
    val dragController = remember { DragController() }
    val registry = remember { DropTargetRegistry() }
    val focus = remember { EditFocus() }

    EditorBackOut(
        editing          = editing,
        onLeave          = { editing = false },
        selection        = selectedSlotState.value != null,
        onClearSelection = { selectedSlotState.value = null; selectedSlotRect = null },
        slotMenu         = handleMenuOpen || slotMenuCursor != null,
        onCloseSlotMenu  = { handleMenuOpen = false; slotMenuCursor = null },
        previewing       = previewing,
        onEndPreview     = { previewing = false },
        gesture          = dragController.gestureUnderWay,
        onCutGesture     = dragController::cutGesture,
    )
    // Every way out of edit mode ends here, so the next entry starts clean.
    LaunchedEffect(editing) {
        if (!editing) {
            dock = Dock.Palette
            clearSlotSelection()
            previewing = false
            resetScreenConfirm = false
            resetAllConfirm = false
            standardConfirm = false
            modulesPanelOpen = false
            presetsOpen = false
            focus.hovered = null
            focus.touched = null
        }
    }
    DisposableEffect(editing) {
        editor.reportEditing(editing)
        onDispose { editor.reportEditing(false) }
    }
    LaunchedEffect(Unit) {
        var seen = signals.editToggle.value
        snapshotFlow { signals.editToggle.value }.collect { tick ->
            if (tick != seen) {
                seen = tick
                editing = !editing
            }
        }
    }

    // What the panels and the selection point at can go under them: an undo, a removal of the container around it,
    // another config. Checked against the document after every change.
    LaunchedEffect(document, config) {
        val target = (dock as? Dock.Props)?.key
        if (target != null && document.contentAt(target.path)?.widgets?.none { it.instanceId == target.instanceId } != false) dock = Dock.Palette
        val slot = selectedSlotState.value
        if (slot != null && document.contentAt(slot) == null) clearSlotSelection()
    }

    val hostDensity = LocalDensity.current
    val shortWindow by remember { derivedStateOf { with(hostDensity) { hostRect.value.height.toDp() } in 1.dp..SHORT_WINDOW } }
    LaunchedEffect(editing) { if (editing && shortWindow) paletteOpen = false }

    // A screen just made: opened once the edit lands, with its settings, so the next thing done is to name it.
    var pendingScreen by remember { mutableStateOf<ConfigId?>(null) }
    LaunchedEffect(pendingScreen, document) {
        val made = pendingScreen ?: return@LaunchedEffect
        if (document.compositions[composition]?.configs?.containsKey(made) != true) return@LaunchedEffect
        pendingScreen = null
        if (config != made) onOpen(made)
        dock = Dock.Screen
    }
    val screen: ScreenTarget? = document.compositions[composition]?.configs?.get(config)?.let { c ->
        ScreenTarget(composition, config, c, made = standard.compositions[composition]?.configs?.containsKey(config) != true)
    }
    val deleteScreen: (ScreenTarget) -> Unit = { doomed ->
        editor.deleteScreen(doomed.composition, doomed.id) { gone ->
            notifications.push(
                sourceKey = "screen-deleted-${doomed.id.value}",
                sender    = Branding.TITLE,
                iconUrl   = null,
                severity  = Severity.Info,
                kind      = Kind.ActionRequired,
                title     = s.text("screenDeletedTitle", doomed.title.ifBlank { s.text("screenUntitled") }),
                actions   = listOf(
                    NotifAction(id = "screen-restore-${doomed.id.value}", label = s.text("screenRestore")) {
                        editor.restoreScreen(doomed.composition, doomed.id, gone)
                        pendingScreen = doomed.id
                    },
                ),
            )
        }
    }

    // The one drop target for the drag under way, worked out once per frame for every widget.
    val liveDocument by rememberUpdatedState(document)
    val dropSlot by remember {
        derivedStateOf {
            val active = dragController.active ?: return@derivedStateOf null
            registry.slotForPoint(active.pointerInWindow)
        }
    }
    // Whether the slot under the drag would refuse what is dragged, which the ghost says while it is over it. Asked
    // when the slot changes, not on every frame of the drag.
    val dropRefused by remember {
        derivedStateOf {
            val path = dropSlot ?: return@derivedStateOf false
            when (val payload = dragController.active?.payload) {
                is DragPayload.PaletteWidget -> editor.missingAt(path, payload.kind).isNotEmpty()
                is DragPayload.ExistingWidget -> payload.source != path && editor.missingAt(path, payload.instance).isNotEmpty()
                null -> false
            }
        }
    }
    val dropTarget by remember {
        derivedStateOf {
            val active = dragController.active ?: return@derivedStateOf null
            val path = dropSlot ?: return@derivedStateOf null
            if (dropRefused) return@derivedStateOf null
            val flow = liveDocument.contentAt(path)?.flow
            path to registry.insertionIndexInSlot(path, active.pointerInWindow, flow)
        }
    }

    val parentWidgetDecorator = LocalWidgetDecorator.current
    val chromeDecorator: WidgetDecorator = remember(arranging, parentWidgetDecorator) {
        if (!arranging) return@remember parentWidgetDecorator
        decorator@{ path, index, descriptor, instance, content ->
            // A slot of a surface is the layout graph's, which this editor does not arrange.
            if (path.slotPath != null) {
                content()
                return@decorator
            }
            val slot = LocalDocument.current?.contentAt(path)
            val drop = dropTarget
            EditableWidgetChrome(
                path         = path,
                index        = index,
                descriptor   = descriptor,
                instance     = instance,
                controller   = dragController,
                editor       = editor,
                registry     = registry,
                focus        = focus,
                flow         = if (slot != null) slot.flow else FlowSpec.Column,
                dropTarget   = drop?.first,
                dropIndex    = drop?.second ?: -1,
                onRemove     = { editor.remove(path, instance.instanceId) },
                onEditProps  = {
                    val key = WidgetKey(path, instance.instanceId)
                    dock = if ((dock as? Dock.Props)?.key == key) Dock.Palette else Dock.Props(key)
                },
                onCommitDrop = { pointer ->
                    val target = registry.slotForPoint(pointer) ?: return@EditableWidgetChrome
                    val flow = liveDocument.contentAt(target)?.flow
                    val at = registry.insertionIndexInSlot(target, pointer, flow)
                    if (target == path) {
                        if (at != index) editor.reorder(path, index, if (at > index) at - 1 else at)
                    } else {
                        editor.move(path, target, instance.instanceId, at)
                    }
                },
                content      = content,
            )
        }
    }
    val emptyDecorator: EmptySlotDecorator = remember(arranging) {
        if (arranging) { path -> if (path.slotPath == null) EmptySlotPlaceholder() } else { _ -> }
    }
    val unknownDecorator: UnknownWidgetDecorator = remember(arranging) {
        if (arranging) {
            { path, _, instance -> UnsupportedWidgetPlaceholder(instance = instance, onRemove = { editor.remove(path, instance.instanceId) }) }
        } else {
            { _, _, _ -> }
        }
    }
    val parentSlotChrome = LocalSlotChromeModifier.current
    val slotChromeFactory: SlotChromeModifier = remember(arranging, parentSlotChrome) {
        if (!arranging) return@remember parentSlotChrome
        { path, slotContent ->
            if (path.slotPath != null) {
                Modifier
            } else {
                slotChromeModifier(
                    path          = path,
                    empty         = slotContent.widgets.isEmpty(),
                    registry      = registry,
                    selectedSlot  = selectedSlotState,
                    onSelect      = { selectedSlotState.value = it; handleMenuOpen = false; slotMenuCursor = null },
                    onContextMenu = { p, off -> selectedSlotState.value = p; slotMenuCursor = off; handleMenuOpen = false },
                    onReportRect  = { selectedSlotRect = it },
                )
            }
        }
    }
    val refusedMount: @Composable (SurfaceId) -> Unit = remember(arranging) {
        if (arranging) { _ -> RefusedMountPlaceholder() } else { _ -> }
    }
    // Remembered: a static local changed on every recomposition of this host would recompose the whole shell.
    val slotBoundsReporter: SlotBoundsReporter = remember(arranging, registry) {
        if (arranging) { path, _, contentRect -> registry.registerSlotContent(path, contentRect) } else { _, _, _ -> }
    }
    val slotBoundsWithdrawal: (NodePath) -> Unit = remember(registry) { { path -> registry.withdrawSlot(path) } }
    val state: EditModeState = remember(editing) { if (editing) EditModeState.On(editor) else EditModeState.Off }

    CompositionLocalProvider(
        // The panels, the palette and the slot menu stand outside the config view that provides it to the widgets.
        LocalDocument           provides document,
        LocalShellChromeBounds  provides chromeBounds,
        LocalEditMode           provides state,
        LocalDragController     provides dragController,
        LocalDropTargetRegistry provides registry,
        LocalEditFocus          provides focus,
        LocalWidgetDecorator    provides chromeDecorator,
        LocalEmptySlotDecorator provides emptyDecorator,
        LocalUnknownWidgetDecorator provides unknownDecorator,
        LocalSlotChromeModifier provides slotChromeFactory,
        LocalSlotMotionMs provides if (arranging) Motion.panelSlide.durationMs else 0,
        // Reflow is a view-time fit, so it is off while arranging: a widget capped to its slot cannot be resized past it.
        LocalPlacementReflow provides !arranging,
        LocalSlotBoundsReporter provides slotBoundsReporter,
        LocalSlotBoundsWithdrawal provides slotBoundsWithdrawal,
        LocalRefusedMount provides refusedMount,
        LocalHiddenWidget provides if (arranging) HiddenWidget else null,
        LocalMapPanOnPrimary provides !arranging,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { hostRect.value = it.boundsInWindow() }
                // The way in for anyone who does not know the chord: a right click on the background, in the final
                // pass so a press a widget took stays the widget's. While arranging the slot chrome owns it.
                .pointerInput(editing) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (editing || event.type != PointerEventType.Press) continue
                            val change = event.changes.first()
                            if (event.buttons.isSecondaryPressed && !change.isConsumed) {
                                entryMenuAt = change.position
                                change.consume()
                            }
                        }
                    }
                }
                // Which widget is meant: the innermost under the pointer, one for the whole window. Observed on the
                // first pass and never consumed, so nothing underneath loses a move.
                .pointerInput(arranging) {
                    if (!arranging) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            when (event.type) {
                                PointerEventType.Exit -> focus.hovered = null
                                else -> if (change.type != PointerType.Touch) {
                                    focus.hovered = registry.widgetForPoint(hostRect.value.topLeft + change.position)
                                }
                            }
                        }
                    }
                },
        ) {
            content()

            entryMenuAt?.let { at ->
                NxContextMenu(anchorInWindow = at, expanded = true, onDismissRequest = { entryMenuAt = null }) {
                    NxMenuItem(label = nexiraStrings().text("editorEnterLayout"), icon = NxIcon.ViewQuilt, hint = "Ctrl+E") {
                        entryMenuAt = null
                        editing = true
                    }
                }
            }

            ContentPaneBox(chromeBounds, hostRect) {
                EditModeVignette(active = editing)

                WidgetModulesPanel(
                    visible   = arranging && modulesPanelOpen,
                    modules   = widgetModules,
                    onDismiss = { modulesPanelOpen = false },
                    modifier  = Modifier.align(Alignment.TopStart).padding(start = 16.dp),
                )
                WidgetPalettePanel(
                    visible   = arranging && paletteOpen && dock == Dock.Palette,
                    onDismiss = { paletteOpen = false },
                    controller = dragController,
                    registry  = registry,
                    editor    = editor,
                    modifier  = Modifier.align(Alignment.TopEnd),
                )
                val props = (dock as? Dock.Props)?.key
                // The left rail's selection style is the person's setting, not the document's, and it belongs to the rail,
                // so the rail's own panel is where it opens from.
                val propsKind = props?.let { key -> document.contentAt(key.path)?.widgets?.firstOrNull { it.instanceId == key.instanceId }?.kind }
                WidgetPropPanel(
                    visible    = arranging && props != null,
                    path       = props?.path,
                    instanceId = props?.instanceId,
                    editor     = editor,
                    onDismiss  = { dock = Dock.Palette },
                    action     = if (propsKind == LEFT_RAIL) s.text("navSelectionTitle") to { dock = Dock.RailStyle(back = dock) } else null,
                    modifier   = Modifier.align(Alignment.TopEnd),
                )
                ScreenPropertiesPanel(
                    visible   = arranging && dock == Dock.Screen && screen != null,
                    spec      = screen,
                    editor    = editor,
                    onDelete  = deleteScreen,
                    onDismiss = { dock = Dock.Palette },
                    modifier  = Modifier.align(Alignment.TopEnd),
                )
                SurfacePropertiesPanel(
                    visible                = arranging && dock is Dock.RailStyle,
                    title                  = s.text("editorSurfShortLeftRail"),
                    customization          = customization,
                    onCustomizationChanged = onCustomizationChanged,
                    onDismiss              = { dock = (dock as? Dock.RailStyle)?.back ?: Dock.Palette },
                    modifier               = Modifier.align(Alignment.TopEnd),
                )

                if (editing && resetScreenConfirm && screen != null) {
                    ConfirmDialog(
                        title   = s.text("editorResetSurfaceTitle"),
                        body    = s.text("editorResetSurfaceBody", screen.title.ifBlank { s.text("screenUntitled") }),
                        confirm = s.text("editorReset"),
                        onConfirm = { editor.resetScreen(composition, config, standard, pagesOf) },
                        onDismiss = { resetScreenConfirm = false },
                    )
                }
                if (editing && resetAllConfirm) {
                    ConfirmDialog(
                        title   = s.text("editorResetAllTitle"),
                        body    = s.text("editorResetAllBody"),
                        confirm = s.text("editorResetAll"),
                        onConfirm = { editor.resetAll(standard) },
                        onDismiss = { resetAllConfirm = false },
                    )
                }
                if (editing && standardConfirm) {
                    ConfirmDialog(
                        title   = s.text("editorBackToStandardTitle"),
                        body    = s.text("editorBackToStandardBody"),
                        confirm = s.text("editorBackToStandard"),
                        onConfirm = { editor.backToStandard() },
                        onDismiss = { standardConfirm = false },
                    )
                }
                PresetManagerPanel(
                    visible       = arranging && presetsOpen,
                    onDismiss     = { presetsOpen = false },
                    onSaveCurrent = { name -> withContext(Dispatchers.IO) { presetRepo.save(name, store.document.value, customization) } },
                    onLoad        = { meta ->
                        presetScope.launch {
                            val preset = withContext(Dispatchers.IO) { presetRepo.load(meta.name) } ?: return@launch
                            editor.applyPreset(preset.document)
                            onCustomizationChanged(preset.customization)
                            presetsOpen = false
                        }
                    },
                    onDelete      = { meta -> withContext(Dispatchers.IO) { presetRepo.delete(meta.name) } },
                    // The folder it is in, in the file manager, to copy from: there is no save dialog to hand it to.
                    onExport      = { meta -> SystemActions.openFolder(meta.sourcePath.parent.toString()) },
                    listProvider  = { presetRepo.list() },
                )
                editor.consent.value?.let {
                    ConfirmDialog(
                        title     = s.text("editorExpertTitle"),
                        body      = s.text("editorExpertBody"),
                        confirm   = s.text("editorExpertConfirm"),
                        danger    = false,
                        onConfirm = { editor.answerConsent(yes = true) },
                        onDismiss = { editor.answerConsent(yes = false) },
                    )
                }
            }

            EditModePill(
                active             = editing,
                paletteOpen        = paletteOpen && dock == Dock.Palette,
                onTogglePalette    = { if (dock == Dock.Palette) paletteOpen = !paletteOpen else { dock = Dock.Palette; paletteOpen = true } },
                previewing         = previewing,
                onTogglePreview    = { previewing = !previewing },
                screenOpen         = dock == Dock.Screen,
                onOpenScreen       = { dock = if (dock == Dock.Screen) Dock.Palette else Dock.Screen },
                onOpenModules      = { modulesPanelOpen = !modulesPanelOpen },
                onOpenPresets      = { presetsOpen = true },
                onNewScreen        = {
                    // Numbered among the screens somebody made, so the first one is the first and not the fifteenth.
                    val shipped = standard.compositions[composition]?.configs?.keys.orEmpty()
                    val made = (document.compositions[composition]?.configs?.keys.orEmpty() - shipped).size + 1
                    pendingScreen = editor.createScreen(composition, s.text("screenDefaultTitle", made), ScreenIcons.DEFAULT, pagesOf)
                },
                resetMenuOpen      = resetMenuOpen,
                onResetMenu        = { resetMenuOpen = it },
                onResetScreen      = { resetScreenConfirm = true },
                onResetAll         = { resetAllConfirm = true },
                ownDocument        = store.own.collectAsState().value,
                onBackToStandard   = { standardConfirm = true },
                canUndo            = editor.canUndo,
                canRedo            = editor.canRedo,
                onUndo             = { editor.undo() },
                onRedo             = { editor.redo() },
                modifier           = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 16.dp),
            )

            if (arranging) {
                SlotSelectionOverlay(
                    selectedSlot     = selectedSlotState.value,
                    selectedSlotRect = selectedSlotRect,
                    handleMenuOpen   = handleMenuOpen,
                    cursorAnchor     = slotMenuCursor,
                    editor           = editor,
                    onOpenHandleMenu = { handleMenuOpen = true },
                    onDismiss        = { handleMenuOpen = false; slotMenuCursor = null },
                )
            }

            DragGhostOverlay(dragController = dragController, refused = dropRefused)
        }
    }
}

/** A question with one red answer, or one plain one where [danger] is false, and a way out. */
@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    val s = nexiraStrings()
    NxAlertDialog(
        onDismissRequest = onDismiss,
        paneTitle        = title,
        title            = { Text(title) },
        text             = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton    = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (danger) NxColor.status(Status.Error) else NxColor.lead())
            }
        },
        dismissButton    = { TextButton(onClick = onDismiss) { Text(s.text("editorCancel")) } },
    )
}

/**
 * The overlays' own box, sitting over the content pane.
 *
 * A composable of its own purely for the size of its restart scope. Reading the
 * two rectangles where they were used put them in the same scope as the whole
 * shell, so dragging the window's edge or swiping the right rail re-ran the
 * pill, the panels and all the editor's wiring on every frame of the gesture.
 * Read here, only this box re-measures.
 */
@Composable
private fun ContentPaneBox(
    bounds: ShellChromeBounds,
    host: State<Rect>,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val insets = paneInsets(bounds.center, host.value, density)
    Box(
        modifier = Modifier.fillMaxSize().padding(start = insets.start, end = insets.end),
        content = content,
    )
}

// ── Top pill ────────────────────────────────────────────────────────────────

@Composable
private fun EditModePill(
    active: Boolean,
    paletteOpen: Boolean,
    onTogglePalette: () -> Unit,
    previewing: Boolean,
    onTogglePreview: () -> Unit,
    screenOpen: Boolean,
    onOpenScreen: () -> Unit,
    onOpenModules: () -> Unit,
    onOpenPresets: () -> Unit,
    onNewScreen: () -> Unit,
    resetMenuOpen: Boolean,
    onResetMenu: (Boolean) -> Unit,
    onResetScreen: () -> Unit,
    onResetAll: () -> Unit,
    /** Whether the person keeps a document of their own, the only case going back to the standard one means anything. */
    ownDocument: Boolean,
    onBackToStandard: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = nexiraStrings()
    val motionMs = Motion.panelSlide.durationMs
    AnimatedVisibility(
        visible  = active,
        enter    = fadeIn(tween(motionMs)) + slideInVertically(tween(motionMs)) { -it },
        exit     = fadeOut(tween(motionMs)) + slideOutVertically(tween(motionMs)) { -it },
        modifier = modifier,
    ) {
        // The pill goes icon-only when the labelled one does not fit, measured rather than guessed.
        FitOrCompact { compact ->
            NxSurface(kind = SurfaceKind.Popup, shape = RoundedCornerShape(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Only the compact form scrolls: the labelled one is measured with no width limit to see whether
                    // it fits, and a scroller refuses an unlimited width.
                    modifier = Modifier
                        .then(if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                        .padding(start = 12.dp, end = if (compact) 8.dp else 4.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Symbol(icon = NxIcon.Tune, contentDescription = null, tint = NxColor.lead(), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    ToolChip(NxIcon.Settings, s.text("editorScreenSettings"), selected = screenOpen, onClick = onOpenScreen, compact = compact)
                    Spacer(Modifier.width(4.dp))
                    ToolChip(
                        icon     = if (previewing) NxIcon.VisibilityOff else NxIcon.Visibility,
                        label    = if (previewing) s.text("editorPreviewHidden") else s.text("editorPreview"),
                        selected = previewing,
                        onClick  = onTogglePreview,
                        compact  = compact,
                    )
                    Spacer(Modifier.width(4.dp))
                    ToolChip(
                        icon     = NxIcon.Widgets,
                        label    = if (paletteOpen) s.text("editorPaletteToggleHide") else s.text("editorWidgets"),
                        selected = paletteOpen,
                        onClick  = onTogglePalette,
                        compact  = compact,
                    )
                    Spacer(Modifier.width(4.dp))
                    // Undo and redo, on the bar as well as on Ctrl+Z, because a chord nobody is told about is a chord
                    // nobody has, and the greyed pair is the only place that says whether there is anything to go back to.
                    ToolChip(NxIcon.Undo, s.text("editorUndo"), selected = false, onClick = onUndo, enabled = canUndo, compact = compact)
                    Spacer(Modifier.width(4.dp))
                    ToolChip(NxIcon.Redo, s.text("editorRedo"), selected = false, onClick = onRedo, enabled = canRedo, compact = compact)
                    Spacer(Modifier.width(4.dp))
                    ToolChip(NxIcon.Add, s.text("editorNewScreen"), selected = false, onClick = onNewScreen, compact = compact)
                    Spacer(Modifier.width(4.dp))
                    ToolChip(NxIcon.Folder, s.text("modulesChip"), selected = false, onClick = onOpenModules, compact = compact)
                    Spacer(Modifier.width(4.dp))
                    ToolChip(NxIcon.Inventory2, s.text("editorPresetsTitle"), selected = false, onClick = onOpenPresets, compact = compact)
                    Spacer(Modifier.width(4.dp))
                    Box {
                        ToolChip(
                            icon        = NxIcon.RestartAlt,
                            label       = s.text("editorReset"),
                            selected    = false,
                            onClick     = { onResetMenu(true) },
                            destructive = true,
                            compact     = compact,
                        )
                        NxContextMenu(expanded = resetMenuOpen, onDismissRequest = { onResetMenu(false) }) {
                            NxMenuItem(s.text("editorScreenSettings")) { onResetMenu(false); onResetScreen() }
                            NxMenuItem(s.text("editorResetAll")) { onResetMenu(false); onResetAll() }
                            if (ownDocument) NxMenuItem(s.text("editorBackToStandard")) { onResetMenu(false); onBackToStandard() }
                        }
                    }
                    if (!compact) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text     = s.text("editorEscHint"),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = NxInk.quiet,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lays out [content] with labels when that fits the width it is given, and in its
 * compact form when it does not. The labelled form is measured with no limit to
 * find out, and only composed a second time, compact, when it is too wide.
 */
@Composable
private fun FitOrCompact(content: @Composable (compact: Boolean) -> Unit) {
    SubcomposeLayout { constraints ->
        val full = subcompose(false) { content(false) }.map { it.measure(Constraints()) }
        val fits = (full.maxOfOrNull { it.width } ?: 0) <= constraints.maxWidth
        val chosen = if (fits) full else subcompose(true) { content(true) }.map { it.measure(constraints.copy(minWidth = 0)) }
        layout(chosen.maxOfOrNull { it.width } ?: 0, chosen.maxOfOrNull { it.height } ?: 0) {
            chosen.forEach { it.place(0, 0) }
        }
    }
}

@Composable
private fun ToolChip(
    icon: IconKey,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val bg = when {
        !enabled    -> NxColor.wash(NxInk.quiet, 0.06f)
        destructive -> NxColor.wash(NxColor.status(Status.Error), 0.12f)
        selected    -> NxColor.wash(NxColor.lead(), 0.18f)
        else        -> NxColor.wash(NxInk.quiet, 0.12f)
    }
    OnFill(bg) {
        val fg = when {
            !enabled    -> NxInk.off
            destructive -> NxColor.status(Status.Error)
            selected    -> NxColor.lead()
            else        -> NxInk.main
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CHIP_SHAPE)
                .background(bg)
                .layerTrigger()
                .clickable(enabled = enabled) { onClick() }
                .padding(horizontal = if (compact) 7.dp else 10.dp, vertical = 5.dp),
        ) {
            Symbol(icon = icon,
                // Compact hides the label, so the icon carries it for a11y.
                contentDescription = if (compact) label else null,
                tint               = fg,
                modifier           = Modifier.size(14.dp),
            )
            if (!compact) {
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
}

private val CHIP_SHAPE = RoundedCornerShape(12.dp)

/**
 * What a surface that refused to open inside itself draws while somebody is
 * arranging: a dashed box saying so, where the surface would have been, so the
 * widget that caused it can be found and moved.
 */
@Composable
private fun RefusedMountPlaceholder() {
    val s = nexiraStrings()
    Box(
        modifier = Modifier
            .padding(8.dp)
            .border(1.dp, NxColor.status(Status.Warning), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(s.text("editorMountRefused"), style = MaterialTheme.typography.labelMedium, color = NxColor.status(Status.Warning))
    }
}

// ── Vignette ────────────────────────────────────────────────────────────────

@Composable
private fun EditModeVignette(active: Boolean) {
    val motionMs = Motion.fade.durationMs
    val alpha by animateFloatAsState(
        targetValue   = if (active) 1f else 0f,
        animationSpec = tween(motionMs),
        label         = "edit-vignette",
    )
    if (alpha <= 0.01f) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .border(
                width = 1.5.dp,
                color = NxColor.wash(NxColor.lead(), 0.35f),
                shape = RoundedCornerShape(0.dp),
            ),
    )
}

// ── Drag ghost ──────────────────────────────────────────────────────────────

@Composable
private fun DragGhostOverlay(dragController: DragController, refused: Boolean) {
    val active = dragController.active ?: return
    val refusal = NxColor.status(Status.Error)
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
            // Over a slot that would not take it, the ghost says so before it is let go, rather than the drop doing
            // nothing.
            if (refused) {
                Box(Modifier.matchParentSize().border(2.dp, refusal, RoundedCornerShape(10.dp)))
            }
        }
        if (refused) {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = active.pointerInWindow.x - overlayOriginInWindow.x + 16.dp.toPx()
                        translationY = active.pointerInWindow.y - overlayOriginInWindow.y + 20.dp.toPx()
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(refusal)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                OnFill(refusal) {
                    Text(nexiraStrings().text("editorDropRefused"), style = MaterialTheme.typography.labelMedium, color = NxColor.on(refusal))
                }
            }
        }
    }
}

/** How far in from the host's own left and right edges the content pane starts. */
internal data class PaneInsets(val start: Dp, val end: Dp)

/**
 * The pane's rectangle expressed as padding on the host's frame.
 *
 * Sideways only. The overlays' distance from the top is the editor's own
 * composition and the top bar is behind them by design, so only the rails, which
 * they must not cover, are measured.
 *
 * Coerced at zero on both sides: a pane wider than the frame it is measured
 * against is a half-laid-out frame rather than a negative gap, and a negative
 * padding is not a thing [androidx.compose.foundation.layout.padding] takes.
 * Nothing reported yet means no inset, which is the full frame -- the same place
 * the overlays sit on a build with no rails.
 *
 * The gaps are then given back, continuously, until what is left between them is
 * at least [MIN_PANE]. A threshold that dropped both insets the moment the pane
 * got narrow was worse than having none: it had no hysteresis, so a reader
 * dragging a rail's width slider watched the panel they were holding teleport
 * across the window and back on every pixel across the boundary. And in the band
 * just under it, dropping the insets put the palette squarely over the right
 * rail, which is itself an editable surface and quite possibly the one being
 * arranged, where simply letting the panel be narrower cost nothing.
 *
 * The end gap gives way first, because the panels hang off that edge and the
 * vignette is all the start gap buys.
 */
internal fun paneInsets(pane: Rect?, host: Rect, density: Density): PaneInsets {
    if (pane == null || host.width <= 0f) return PaneInsets(0.dp, 0.dp)
    fun gap(px: Float): Dp = with(density) { px.coerceAtLeast(0f).toDp() }
    val room = (host.width - with(density) { MIN_PANE.toPx() }).coerceAtLeast(0f)
    var start = (pane.left - host.left).coerceAtLeast(0f)
    var end = (host.right - pane.right).coerceAtLeast(0f)
    if (start + end > room) {
        end = (room - start).coerceAtLeast(0f)
        if (start > room) start = room
    }
    return PaneInsets(start = gap(start), end = gap(end))
}

/**
 * How little room the overlays will leave themselves.
 *
 * The narrowest a floating panel goes under its own resize (DockSize.MIN), so the
 * clamp stops exactly where the panel would stop anyway. It is not the widest
 * panel: one pulled out to 560 has been pulled there deliberately, and holding
 * the whole pane open for it would cover the rails on any ordinary window.
 */
private val MIN_PANE = 200.dp

/** Below this height the editor starts with its palette closed. */
private val SHORT_WINDOW = 520.dp
