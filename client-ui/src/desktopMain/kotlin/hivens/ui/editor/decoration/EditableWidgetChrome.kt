package hivens.ui.editor.decoration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.editor.canvasDragOffset
import hivens.ui.editor.canvasResizeSize
import hivens.ui.editor.cubeDragCell
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuItem
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalCanvasSlotSizeDp
import hivens.widget.api.LocalCubeGeometry
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.GridCell
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import hivens.widget.model.WidgetInstance
import hivens.widget.model.traverse
import java.awt.Cursor

// Wraps a single widget with edit-mode chrome: whole-body drag overlay,
// remove button (hover-only, hidden when non-removable), configure "tune"
// gear (hover -- opens props + the universal backing controls), a resize
// handle, faint border outline, and a drop indicator.
//
// Phase G: the chrome follows the slot's orientation. In a Column slot it
// wraps in a Column with horizontal drop bars above/below; in a Row slot
// it wraps in a Row with vertical drop bars left/right. The hover buttons
// live in a Box-scoped inner section so they use plain BoxScope `.align`
// regardless of the outer Row/Column.
//
// The whole wrapper is also a drop-target bounds-reporter for its own
// rect -- the registry uses this to compute insertion-index hit-tests
// during a drag.
@Composable
fun EditableWidgetChrome(
    path: SlotPath,
    index: Int,
    descriptor: WidgetDescriptor,
    instance: WidgetInstance,
    controller: DragController,
    editController: EditModeController,
    registry: DropTargetRegistry,
    orientation: SlotOrientation,
    onRemove: () -> Unit,
    onEditProps: () -> Unit,
    onCommitDrop: (committedPointer: Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    val s = LocalStrings.current
    val chromeMotionMs = Motion.fade.durationMs
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    var widgetWindowBounds by remember { mutableStateOf<Rect?>(null) }
    var forceRemoveOpen by remember { mutableStateOf(false) }
    val activeDrag = controller.active
    val isThisDragging = (activeDrag?.payload as? DragPayload.ExistingWidget)
        ?.instance?.instanceId == instance.instanceId

    val isRow = orientation == SlotOrientation.Row
    val isCanvas = orientation == SlotOrientation.Canvas
    val isCubeGrid = orientation == SlotOrientation.CubeGrid
    // Live placement read from inside the long-lived drag gesture: the
    // pointerInput is keyed only on instanceId so it does not restart
    // mid-drag, and without this the gesture would capture a stale start
    // placement on the second drag of the same widget.
    val liveCanvas = rememberUpdatedState(instance.canvas)
    // Live canvas slot size for the move-clamp (published by SlotRenderer's
    // Canvas branch; Zero outside a Canvas slot disables clamping).
    val liveSlotSize = rememberUpdatedState(LocalCanvasSlotSizeDp.current)
    // Cube-grid move: the widget's current cell + the slot's cell geometry, read
    // live so the long-lived gesture sees the latest values. cubeDrag is the
    // in-flight visual translation, committed to a target cell on release.
    val liveCell = rememberUpdatedState(instance.cell)
    val cubeGeo = rememberUpdatedState(LocalCubeGeometry.current)
    // Same reason, for the values the flow-reorder branch hands on: a gesture that
    // does not restart between drags would announce the position the widget held
    // when it was first dragged, and commit through the host's first drop handler
    // rather than the one built against the layout as it stands.
    val liveIndex = rememberUpdatedState(index)
    val liveInstance = rememberUpdatedState(instance)
    val liveCommitDrop = rememberUpdatedState(onCommitDrop)
    var cubeDrag by remember { mutableStateOf(Offset.Zero) }
    // Cursor anchor for the right-click context menu (null = closed).
    var menuAnchor by remember { mutableStateOf<Offset?>(null) }
    val resizeCursor = remember { PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR)) }

    // Drop-indicator hit test. Reading controller.active recomposes on
    // every pointer update; traverse + registry queries are O(depth +
    // widgets-in-slot) and cheap enough to do per-frame for the few
    // dozen widgets a surface can hold.
    val graph = LocalLayoutGraph.current
    val slotCount = graph.traverse(path)?.widgets?.size ?: 0
    val isLastInSlot = index == slotCount - 1
    val dropTargetPath = activeDrag?.let { registry.slotForPoint(it.pointerInWindow) }
    val dropInsertionIdx = if (activeDrag != null && dropTargetPath == path) {
        registry.insertionIndexInSlot(path, activeDrag.pointerInWindow, orientation)
    } else -1
    val showIndicatorBefore = dropInsertionIdx == index
    val showIndicatorAfter  = isLastInSlot && dropInsertionIdx == slotCount

    // Nesting depth -> subtle border alpha boost. Depth 0 (root surface
    // slot) keeps the original 0.18/0.55 alpha; each level adds 0.06
    // and we clip at 0.40/0.85 so deep stacks stay readable.
    val depthBoost = (path.nested.size * 0.06f).coerceAtMost(0.22f)

    // The ghost lambda is invoked by DragGhostOverlay at the host
    // level -- outside the surface composable's CompositionLocalProvider
    // chain. Widgets like HomeNewRecent read surface-scoped locals
    // (LocalHomeNewContext, LocalLibraryContext, ...) and would throw
    // when the ghost recomposes them. Snapshot the locals here and
    // restore them inside the ghost so the widget renders identically
    // wherever it lands.
    val capturedLocals = currentCompositionLocalContext

    // Drop this widget's drop-target rect when it leaves composition (deleted /
    // moved): the registry persists across the edit session, so without this a
    // phantom rect keeps winning hit-tests at the widget's old spot.
    DisposableEffect(path, instance.instanceId) {
        onDispose { registry.unregisterWidget(path, instance.instanceId) }
    }

    // Source widget fades to 30% while being dragged -- the ghost is
    // doing the work on top. Once drag ends, we ramp back smoothly.
    val sourceAlpha by animateFloatAsState(
        targetValue   = if (isThisDragging) 0.30f else 1f,
        animationSpec = tween(chromeMotionMs),
        label         = "edit-source-alpha",
    )
    val borderAlpha by animateFloatAsState(
        // Resting outline in edit mode: every widget's bounds must stay legible, since the
        // hover affordance buttons that used to advertise "this is an editable widget" were
        // removed -- without a resting cue the user can't tell a widget from the empty slot
        // around it (and right-clicks / drags then land on the slot). It strengthens under
        // the pointer. Drawn inside the widget's bounds (drawWithContent), so no reflow.
        targetValue   = if (isHovered) 0.55f + depthBoost else 0.22f + depthBoost,
        animationSpec = tween(chromeMotionMs),
        label         = "edit-border-alpha",
    )
    // Captured here because NxTheme.colors is a @Composable read; the draw lambda
    // applies the animated alpha (a snapshot read, so it redraws without recomposing).
    val borderColor = NxTheme.colors.primary

    // Bordered widget + hover handles. Box-scoped so the AnimatedVisibility
    // buttons use plain BoxScope `.align` -- no this@Column / this@Row
    // qualifier, which lets the outer wrapper be either orientation.
    val widgetBox: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                // A cube widget owns its whole cell: fill it so the hover border and the
                // matchParentSize body overlay cover the cell, not just the (smaller)
                // content -- otherwise a right-click on the empty cell area / gutter falls
                // through to the slot chrome and opens the layout menu. Edit-mode only
                // (the decorator is identity in production, so the cell renders as before).
                .then(if (isCubeGrid) Modifier.fillMaxSize() else Modifier)
                // Live cube-move translation; zero except while dragging a cube widget.
                .graphicsLayer { translationX = cubeDrag.x; translationY = cubeDrag.y }
                .hoverable(interaction)
                // Hover border drawn INSIDE the widget's own bounds (drawWithContent,
                // not Modifier.border on a padded box) so edit mode never reflows the
                // layout -- the old padding(4) + padding(2) added ~12dp per widget and
                // shifted the whole surface down on entering edit mode.
                .drawWithContent {
                    drawContent()
                    val strokePx = 1.dp.toPx()
                    val edge     = strokePx / 2f
                    val radius   = 8.dp.toPx()
                    drawRoundRect(
                        color        = borderColor.copy(alpha = borderAlpha),
                        topLeft      = Offset(edge, edge),
                        size         = Size(size.width - strokePx, size.height - strokePx),
                        cornerRadius = CornerRadius(radius, radius),
                        style        = Stroke(width = strokePx),
                    )
                }
                .onGloballyPositioned { coords: LayoutCoordinates ->
                    // Register the widget's own bounds for the drop hit-test.
                    val rect = coords.boundsInWindow()
                    widgetWindowBounds = rect
                    registry.registerWidget(path, instance.instanceId, index, rect)
                },
        ) {
            Box(Modifier.alpha(sourceAlpha)) { content() }

            // Whole-widget drag surface -- no separate handle. A press anywhere
            // on the body (above the content, below the hover affordances) drags
            // the widget: reorder in a flow slot, absolute move on a Canvas slot.
            // The gesture consumes the press, so the widget's own controls stay
            // inert while editing -- you arrange the widget, you do not operate
            // it. The corner affordances sit above this overlay and still tap.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(instance.instanceId) {
                        // Right-click detection. The drag gesture below starts with
                        // awaitFirstDown, which fires ONLY on the primary (left) button --
                        // so a bare right-click never reached the widget and fell through to
                        // the slot chrome (which opened the layout menu). Detect the secondary
                        // press with raw events instead, consume it on the Main pass (so the
                        // slot's Final-pass handler sees it consumed and defers), then open the
                        // widget context menu at the cursor.
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
                                    change.consume()
                                    widgetWindowBounds?.let { menuAnchor = it.topLeft + change.position }
                                }
                            }
                        }
                    }
                    // Keyed on the slot and its orientation as well as the widget:
                    // which branch this gesture takes IS the orientation, and a
                    // slot flipped from Column to CubeGrid under a widget left the
                    // running gesture reordering a grid. Restarting between drags
                    // costs nothing; mid-drag neither value can change.
                    .pointerInput(instance.instanceId, path, orientation) {
                        awaitEachGesture {
                            // requireUnconsumed: yield to the hover affordances
                            // and resize handle stacked above (each consumes its
                            // own press), so resizing does not also drag the body.
                            val down = awaitFirstDown(requireUnconsumed = true)
                            // Claim the press so a tap never reaches the content.
                            down.consume()
                            when {
                                isCanvas -> {
                                    // Absolute move: apply each frame's delta to the
                                    // current (already-clamped) position and re-seat,
                                    // so dragging past an edge and back responds at
                                    // once -- no dead-zone from an unbounded
                                    // accumulator. canvasDragOffset clamps the output.
                                    val p = liveCanvas.value
                                    var curX = p?.x ?: 0f
                                    var curY = p?.y ?: 0f
                                    drag(down.id) { change ->
                                        val slot = liveSlotSize.value
                                        val wb = widgetWindowBounds
                                        val (nx, ny) = canvasDragOffset(
                                            curX, curY,
                                            change.positionChange().x, change.positionChange().y,
                                            density,
                                            slotWDp   = slot.width,
                                            slotHDp   = slot.height,
                                            widgetWDp = (wb?.width ?: 0f) / density,
                                            widgetHDp = (wb?.height ?: 0f) / density,
                                        )
                                        curX = nx
                                        curY = ny
                                        editController.setWidgetOffset(path, instance.instanceId, nx, ny)
                                        change.consume()
                                    }
                                }
                                isCubeGrid -> {
                                    // Cube move: follow the pointer live (cubeDrag), then
                                    // commit to the nearest cell on release. placeWidgetInCell
                                    // resolves collisions + compacts, so the grid reflows.
                                    val start = liveCell.value ?: GridCell()
                                    var acc = Offset.Zero
                                    drag(down.id) { change ->
                                        acc += change.positionChange()
                                        cubeDrag = acc
                                        change.consume()
                                    }
                                    cubeGeo.value?.let { geo ->
                                        val (col, row) = cubeDragCell(
                                            start.col, start.row,
                                            acc.x, acc.y, density,
                                            geo.cellWidthDp, geo.gutterDp, geo.columns,
                                        )
                                        editController.moveWidgetToCell(path, instance.instanceId, col, row, geo.columns)
                                    }
                                    cubeDrag = Offset.Zero
                                }
                                else -> {
                                    // Flow reorder: drive the existing DnD controller
                                    // once past the touch slop (a tap is swallowed).
                                    val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                                        ?: return@awaitEachGesture
                                    val bounds = widgetWindowBounds ?: return@awaitEachGesture
                                    controller.begin(
                                        payload         = DragPayload.ExistingWidget(path, liveIndex.value, liveInstance.value),
                                        pointerInWindow = bounds.topLeft + slop.position,
                                        pickupOffset    = slop.position,
                                        widgetSize      = Offset(bounds.width, bounds.height),
                                        ghost           = { CompositionLocalProvider(capturedLocals) { content() } },
                                    )
                                    // Accumulated from the start, not re-read from the
                                    // widget's live bounds each frame. The drop indicator
                                    // is a real layout child, so the moment the hit-test
                                    // names this slot the widget shifts by its height --
                                    // and a pointer measured against the widget's own
                                    // origin then jumped by that much, flipping the
                                    // hit-test back. The two branches above already do it
                                    // this way; this one was the odd one out.
                                    var last = bounds.topLeft + slop.position
                                    drag(slop.id) { change ->
                                        last += change.positionChange()
                                        controller.update(last)
                                        change.consume()
                                    }
                                    liveCommitDrop.value(last)
                                    controller.end()
                                }
                            }
                        }
                    },
            )

            // SE resize handle (hover-only) -> setWidgetSize. Works on any slot:
            // on a Canvas slot it sizes the free-placed widget; in a flow slot
            // SlotRenderer applies the size. Seizes the measured px as the
            // baseline when the placement size is 0 (intrinsic) so the first
            // drag does not jump from nothing.
            AnimatedVisibility(
                visible  = isHovered && !isCubeGrid,
                enter    = fadeIn(tween(chromeMotionMs)),
                exit     = fadeOut(tween(chromeMotionMs)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(3.dp),
            ) {
                Surface(
                    color    = NxTheme.colors.primary.copy(alpha = 0.85f),
                    shape    = RoundedCornerShape(5.dp),
                    modifier = Modifier
                        .size(16.dp)
                        .pointerHoverIcon(resizeCursor)
                        .pointerInput(instance.instanceId) {
                            // Custom gesture (not detectDragGestures) so the press
                            // is consumed -- otherwise the body drag overlay also
                            // claims it and the widget jumps / size resets on the
                            // next drag. Start size is read live each gesture.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val p = liveCanvas.value
                                val wb = widgetWindowBounds
                                val startW = (p?.width ?: 0f).takeIf { it > 0f }
                                    ?: ((wb?.width ?: 0f) / density)
                                val startH = (p?.height ?: 0f).takeIf { it > 0f }
                                    ?: ((wb?.height ?: 0f) / density)
                                var accX = 0f
                                var accY = 0f
                                drag(down.id) { change ->
                                    accX += change.positionChange().x
                                    accY += change.positionChange().y
                                    val (nw, nh) = canvasResizeSize(startW, startH, accX, accY, density)
                                    editController.setWidgetSize(path, instance.instanceId, nw, nh)
                                    change.consume()
                                }
                            }
                        },
                ) {
                    Symbol(icon = NxIcon.OpenInFull,
                        contentDescription = null,
                        tint               = NxTheme.colors.onPrimary,
                        modifier           = Modifier.size(11.dp).padding(0.dp),
                    )
                }
            }
        }
    }

    when {
        // Canvas: the widget is positioned by SlotRenderer's outer offset Box.
        // No flow wrapper and no drop bars -- insertion index is meaningless
        // under free placement.
        isCanvas || isCubeGrid -> widgetBox()
        isRow -> Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.Top) {
            if (showIndicatorBefore) DropIndicator(isRow = true)
            widgetBox()
            if (showIndicatorAfter) DropIndicator(isRow = true)
        }
        else -> Column(modifier = Modifier.fillMaxWidth()) {
            if (showIndicatorBefore) DropIndicator(isRow = false)
            widgetBox()
            if (showIndicatorAfter) DropIndicator(isRow = false)
        }
    }

    // Right-click context menu (replaces the old hover affordance buttons): the
    // widget's actions, anchored at the cursor. Any secondary press opens it,
    // drag or no drag: cube slots have no resize gesture. The geometry for one is
    // written (cubeResizeSpan) and nothing calls it, which is worth saying here
    // because the comment that stood in this place described the gesture as
    // though it worked.
    menuAnchor?.let { anchor ->
        NxContextMenu(anchorInWindow = anchor, expanded = true, onDismissRequest = { menuAnchor = null }) {
            WidgetContextMenuContent(
                isCanvas       = isCanvas,
                removable      = descriptor.removable,
                path           = path,
                instanceId     = instance.instanceId,
                editController = editController,
                onConfigure    = { menuAnchor = null; onEditProps() },
                onRemove       = { menuAnchor = null; onRemove() },
                onForceRemove  = { menuAnchor = null; forceRemoveOpen = true },
                onClose        = { menuAnchor = null },
            )
        }
    }

    if (forceRemoveOpen) {
        AlertDialog(
            onDismissRequest = { forceRemoveOpen = false },
            containerColor   = NxTheme.colors.surface,
            title            = { Text(s.editorForceRemoveTitle) },
            text             = {
                Text(
                    text = s.editorForceRemoveBody(s.widgetLabel(descriptor.displayName)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forceRemoveOpen = false
                    onRemove()
                }) { Text(s.editorDelete, color = NxTheme.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { forceRemoveOpen = false }) { Text(s.editorCancel) }
            },
        )
    }
}

// The widget's right-click context menu body (replaces the old hover affordance
// buttons): configure, z-order on a Canvas, and remove / force-remove. Reads the
// graph live for the z bounds; each item closes the menu.
@Composable
private fun WidgetContextMenuContent(
    isCanvas: Boolean,
    removable: Boolean,
    path: SlotPath,
    instanceId: String,
    editController: EditModeController,
    onConfigure: () -> Unit,
    onRemove: () -> Unit,
    onForceRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val graph = LocalLayoutGraph.current
    NxMenuItem(s.editorConfigure) { onConfigure() }
    if (isCanvas) {
        NxMenuItem(s.editorToFront) {
            val maxZ = graph.traverse(path)?.widgets?.maxOfOrNull { it.canvas?.z ?: 0 } ?: 0
            editController.setWidgetZ(path, instanceId, maxZ + 1); onClose()
        }
        NxMenuItem(s.editorToBack) {
            val minZ = graph.traverse(path)?.widgets?.minOfOrNull { it.canvas?.z ?: 0 } ?: 0
            editController.setWidgetZ(path, instanceId, minZ - 1); onClose()
        }
    }
    if (removable) NxMenuItem(s.editorDelete) { onRemove() }
    else NxMenuItem(s.editorForceRemove) { onForceRemove() }
}

// Drop insertion bar. Horizontal (full width, 2dp tall) for a Column
// slot; vertical (full height, 2dp wide) for a Row slot.
@Composable
private fun DropIndicator(isRow: Boolean) {
    if (isRow) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .padding(vertical = 4.dp)
                .background(NxTheme.colors.primary),
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .padding(horizontal = 4.dp)
                .background(NxTheme.colors.primary),
        )
    }
}
