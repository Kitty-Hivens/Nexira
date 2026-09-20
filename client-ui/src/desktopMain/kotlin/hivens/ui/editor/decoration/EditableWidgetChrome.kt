package hivens.ui.editor.decoration

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import hivens.ui.editor.EditModeController
import hivens.ui.editor.ResizeBounds
import hivens.ui.editor.ResizeEdge
import hivens.ui.editor.canvasResize
import hivens.ui.editor.guideLeadDp
import hivens.ui.editor.placementDragOffset
import hivens.ui.editor.gridDragCell
import hivens.ui.editor.gridResizeSpan
import hivens.ui.editor.dnd.DragController
import hivens.ui.editor.dnd.DragPayload
import hivens.ui.editor.dnd.DropTargetRegistry
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxContextMenu
import hivens.ui.nx.NxMenuItem
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.widget.api.LocalPlacementSlotSizeDp
import hivens.widget.api.LocalGridGeometry
import hivens.widget.api.LocalLayoutGraph
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.FlowSpec
import hivens.widget.model.Placement
import hivens.widget.model.anchorDragSignX
import hivens.widget.model.anchorDragSignY
import hivens.widget.model.anchorHorizontalBias
import hivens.widget.model.anchorVerticalBias
import hivens.widget.model.parseAnchor
import hivens.widget.model.SlotPath
import hivens.widget.model.WidgetInstance
import hivens.widget.model.traverse
import java.awt.Cursor

// Wraps a single widget with edit-mode chrome: whole-body drag overlay,
// remove button (hover-only, hidden when non-removable), configure "tune"
// gear (hover -- opens props + the universal backing controls), a resize
// handle, faint border outline, and a drop indicator.
//
// The chrome follows the slot's mode. In a vertical flow it wraps in a Column
// with horizontal drop bars above and below; in an unwrapped horizontal flow, a
// Row with vertical bars; in a placement slot, neither, because an insertion
// index means nothing where position is stored rather than derived. The hover
// affordances live in a Box-scoped inner section so they use plain BoxScope
// `.align` whatever the outer wrapper turned out to be.
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
    flow: FlowSpec?,
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

    val isRow = flow?.rowLike == true
    val resizable = flow?.uniformGrid != true
    // A placement slot is one the flow is absent from. Whether it measures in
    // cells or in dp is the lattice geometry's answer, published by the renderer
    // and null when the slot is free.
    val isPlaced = flow == null
    // Live placement read from inside the long-lived drag gesture: the
    // pointerInput is keyed only on instanceId so it does not restart
    // mid-drag, and without this the gesture would capture a stale start
    // placement on the second drag of the same widget.
    val livePlacement = rememberUpdatedState(instance.placement)
    // Live canvas slot size for the move-clamp (published by SlotRenderer's
    // Canvas branch; Zero outside a Canvas slot disables clamping).
    val liveSlotSize = rememberUpdatedState(LocalPlacementSlotSizeDp.current)
    // The slot's lattice geometry, read live so the long-lived gesture sees the
    // latest values. Null in a free placement slot, where the unit is already the
    // dp. latticeDrag is the in-flight visual translation, committed to a cell on
    // release.
    val gridGeo = rememberUpdatedState(LocalGridGeometry.current)
    // Same reason, for the values the flow-reorder branch hands on: a gesture that
    // does not restart between drags would announce the position the widget held
    // when it was first dragged, and commit through the host's first drop handler
    // rather than the one built against the layout as it stands.
    val liveIndex = rememberUpdatedState(index)
    val liveInstance = rememberUpdatedState(instance)
    val liveCommitDrop = rememberUpdatedState(onCommitDrop)
    var latticeDrag by remember { mutableStateOf(Offset.Zero) }
    // What this widget says it needs and can use. The gesture is held to it, the
    // renderer bounds by it, and while a handle is down the two extremes are drawn
    // so the range is visible before the drag ends rather than discovered by it.
    val sizing = descriptor.sizing
    val widthBounds = remember(sizing) { ResizeBounds.of(sizing.minWidth, sizing.maxWidth) }
    val heightBounds = remember(sizing) { ResizeBounds.of(sizing.minHeight, sizing.maxHeight) }
    var resizing by remember { mutableStateOf(false) }
    // Read outside the draw lambda: gridGeo is a State and the guides only apply
    // where the unit is the dp.
    val latticeGeo = gridGeo.value
    // Cursor anchor for the right-click context menu (null = closed).
    var menuAnchor by remember { mutableStateOf<Offset?>(null) }

    // Drop-indicator hit test. Reading controller.active recomposes on
    // every pointer update; traverse + registry queries are O(depth +
    // widgets-in-slot) and cheap enough to do per-frame for the few
    // dozen widgets a surface can hold.
    val graph = LocalLayoutGraph.current
    val slotCount = graph.traverse(path)?.widgets?.size ?: 0
    val isLastInSlot = index == slotCount - 1
    val dropTargetPath = activeDrag?.let { registry.slotForPoint(it.pointerInWindow) }
    val dropInsertionIdx = if (activeDrag != null && dropTargetPath == path) {
        registry.insertionIndexInSlot(path, activeDrag.pointerInWindow, flow)
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
    // The corner the resize pivots on, which is the corner the guides hang from.
    val guideAnchor = instance.placement?.anchor ?: Placement.TOP_START
    val hBiasForGuides = anchorHorizontalBias(parseAnchor(guideAnchor))
    val vBiasForGuides = anchorVerticalBias(parseAnchor(guideAnchor))

    // Being landed on. Free placement lets widgets overlap and will keep letting
    // them, so this is the warning and not a refusal: the border turns while the
    // gesture is live, and where it goes is still the person's call.
    val isOverlapped = instance.instanceId in registry.overlapped
    val overlapColor = NxTheme.colors.error
    val overlapAlpha by animateFloatAsState(
        targetValue   = if (isOverlapped) 0.9f else 0f,
        animationSpec = tween(chromeMotionMs),
        label         = "edit-overlap-alpha",
    )

    // Bordered widget + hover handles. Box-scoped so the AnimatedVisibility
    // buttons use plain BoxScope `.align` -- no this@Column / this@Row
    // qualifier, which lets the outer wrapper be either orientation.
    val widgetBox: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                // A widget in a lattice owns its whole cell: fill it so the hover border and the
                // matchParentSize body overlay cover the cell, not just the (smaller)
                // content -- otherwise a right-click on the empty cell area / gutter falls
                // through to the slot chrome and opens the layout menu. Edit-mode only
                // (the decorator is identity in production, so the cell renders as before).
                .then(if (isPlaced && gridGeo.value != null) Modifier.fillMaxSize() else Modifier)
                // Live lattice-move translation; zero except while dragging in one.
                .graphicsLayer { translationX = latticeDrag.x; translationY = latticeDrag.y }
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
                    // Where the handle may still go, while it is down. Two dashed
                    // rectangles hung off the corner the resize pivots on: the
                    // widget's own floor and its own ceiling. Nothing said where a
                    // drag could stop until it stopped, so the only way to find a
                    // limit was to hit it.
                    // Free placement only. A lattice resizes in whole cells through
                    // gridResizeSpan, which these bounds are not passed to, so a
                    // guide there would draw a limit the gesture does not honour.
                    if (resizing && latticeGeo == null) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                        val guide = borderColor.copy(alpha = 0.5f)
                        // An axis with no declared limit keeps the widget's own
                        // extent there, so the guide marks one axis without
                        // claiming anything about the other.
                        fun mark(wDp: Float, hDp: Float) {
                            val w = if (wDp.isFinite()) wDp.dp.toPx() else size.width
                            val h = if (hDp.isFinite()) hDp.dp.toPx() else size.height
                            drawRoundRect(
                                color = guide,
                                topLeft = Offset(
                                    guideLeadDp(size.width, w, hBiasForGuides),
                                    guideLeadDp(size.height, h, vBiasForGuides),
                                ),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(radius, radius),
                                style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
                            )
                        }
                        // Only for a limit the widget declared. An axis that said
                        // nothing draws no line there, because the line would be
                        // the editor's own fallback presented as the widget's word.
                        // Per axis, not per guide. ResizeBounds fills an undeclared
                        // floor with the editor's own 48, which is a real limit but
                        // not the widget's word, and drawing it said the column may
                        // be squashed to 48 when nobody had said any such thing.
                        // NaN stands for "said nothing" the way infinity does above.
                        fun floor(declared: Int, bound: Float) = if (declared > 0) bound else Float.NaN
                        if (sizing.minWidth > 0 || sizing.minHeight > 0) {
                            mark(floor(sizing.minWidth, widthBounds.minDp), floor(sizing.minHeight, heightBounds.minDp))
                        }
                        if (sizing.maxWidth > 0 || sizing.maxHeight > 0) {
                            mark(widthBounds.maxDp, heightBounds.maxDp)
                        }
                    }
                    // Over the resting outline rather than instead of it, and
                    // thicker, so the pair being warned about reads at a glance
                    // across a surface where every widget already has a border.
                    if (overlapAlpha > 0f) {
                        val warn = 2.dp.toPx()
                        drawRoundRect(
                            color        = overlapColor.copy(alpha = overlapAlpha),
                            topLeft      = Offset(warn / 2f, warn / 2f),
                            size         = Size(size.width - warn, size.height - warn),
                            cornerRadius = CornerRadius(radius, radius),
                            style        = Stroke(width = warn),
                        )
                    }
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
                    // slot flipped from a flow to placement under a widget left the
                    // running gesture reordering a lattice. Restarting between drags
                    // costs nothing; mid-drag neither value can change.
                    .pointerInput(instance.instanceId, path, flow) {
                        awaitEachGesture {
                            // requireUnconsumed: yield to the hover affordances
                            // and resize handle stacked above (each consumes its
                            // own press), so resizing does not also drag the body.
                            val down = awaitFirstDown(requireUnconsumed = true)
                            // Claim the press so a tap never reaches the content.
                            down.consume()
                            when {
                                // The lattice branch goes first, because a lattice
                                // slot is also a placement slot and the finer answer
                                // has to win.
                                // Which way a drag moves the number depends on the
                                // corner it is measured from: an offset anchored to
                                // the end edge is an inset, so pulling away from that
                                // edge has to make it larger, not smaller. Without
                                // this the widget walked the wrong way on every axis
                                // whose anchor is not at the start.
                                isPlaced && gridGeo.value != null -> {
                                    val a = livePlacement.value?.anchor ?: Placement.TOP_START
                                    val signX = anchorDragSignX(a)
                                    val signY = anchorDragSignY(a)
                                    // Lattice move: follow the pointer live, then commit
                                    // to a cell on release. Nobody else moves: a target
                                    // that collides snaps to the nearest free cell.
                                    val start = livePlacement.value ?: Placement()
                                    var acc = Offset.Zero
                                    drag(down.id) { change ->
                                        acc += change.positionChange()
                                        latticeDrag = acc
                                        change.consume()
                                    }
                                    gridGeo.value?.let { geo ->
                                        val (col, row) = gridDragCell(
                                            start.x.toInt(), start.y.toInt(),
                                            acc.x * signX, acc.y * signY, density,
                                            geo.cellDp, geo.gutterDp, geo.columns,
                                        )
                                        editController.moveWidgetInGrid(path, instance.instanceId, col, row, geo.columns)
                                    }
                                    latticeDrag = Offset.Zero
                                }
                                isPlaced -> {
                                    val a = livePlacement.value?.anchor ?: Placement.TOP_START
                                    val signX = anchorDragSignX(a)
                                    val signY = anchorDragSignY(a)
                                    // Free move: apply each frame's delta to the
                                    // current (already-clamped) position and re-seat,
                                    // so dragging past an edge and back responds at
                                    // once -- no dead-zone from an unbounded
                                    // accumulator. canvasDragOffset clamps the output.
                                    val p = livePlacement.value
                                    var curX = p?.x ?: 0f
                                    var curY = p?.y ?: 0f
                                    drag(down.id) { change ->
                                        val slot = liveSlotSize.value
                                        val wb = widgetWindowBounds
                                        val (nx, ny) = placementDragOffset(
                                            curX, curY,
                                            change.positionChange().x * signX, change.positionChange().y * signY,
                                            density,
                                            slotWDp   = slot.width,
                                            slotHDp   = slot.height,
                                            widgetWDp = (wb?.width ?: 0f) / density,
                                            widgetHDp = (wb?.height ?: 0f) / density,
                                            hBias     = anchorHorizontalBias(a),
                                            vBias     = anchorVerticalBias(a),
                                        )
                                        curX = nx
                                        curY = ny
                                        editController.setWidgetOffset(path, instance.instanceId, nx, ny)
                                        // Who this is currently on top of, said while the
                                        // gesture is live. The bounds are a frame behind the
                                        // write, which for a warning colour is close enough
                                        // and costs no extra measurement.
                                        registry.publishOverlap(
                                            wb?.let { registry.overlapping(path, it, instance.instanceId) }.orEmpty(),
                                        )
                                        change.consume()
                                    }
                                    registry.publishOverlap(emptySet())
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

            // Resize handles (hover-only). In a placement slot they size the widget,
            // in dp or in whole cells depending on what the slot measures in; in an
            // unwrapped flow SlotRenderer applies the size as an upper bound. They are
            // absent from a uniform wrapped flow, which sizes its own cells and would
            // take the number without ever reading it. Each seizes the measured px as
            // the baseline when the stored size is 0 (intrinsic) so the first drag
            // does not jump from nothing.
            //
            // Eight of them, not one. The lone bottom-right corner could only grow a
            // widget down and to the right, so pulling the left edge in meant moving
            // the widget and then resizing it and then moving it back, and there was
            // no way at all to grow it upward from where it sat.
            //
            // A lattice keeps the single corner. Its unit is a whole cell and its
            // move and its resize are separate model operations that each refuse to
            // disturb a neighbour, so a leading edge there is a different gesture
            // rather than the same one mirrored. Free placement is what the shell
            // ships and what this is for.
            val edges = if (gridGeo.value != null) listOf(ResizeEdge.SouthEast) else ResizeEdge.entries
            edges.forEach { edge ->
                AnimatedVisibility(
                    visible  = isHovered && resizable,
                    enter    = fadeIn(tween(chromeMotionMs)),
                    exit     = fadeOut(tween(chromeMotionMs)),
                    modifier = Modifier.align(edge.alignment()).padding(2.dp),
                ) {
                    Surface(
                        color    = NxTheme.colors.primary.copy(alpha = if (edge.isCorner()) 0.85f else 0.6f),
                        shape    = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .size(edge.handleSize())
                            .pointerHoverIcon(remember(edge) { PointerIcon(Cursor(edge.cursor())) })
                            .pointerInput(instance.instanceId, edge) {
                                // Custom gesture (not detectDragGestures) so the press
                                // is consumed -- otherwise the body drag overlay also
                                // claims it and the widget jumps / size resets on the
                                // next drag. Start geometry is read live each gesture.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    down.consume()
                                    resizing = true
                                    val p = livePlacement.value
                                    val wb = widgetWindowBounds
                                    val geo = gridGeo.value
                                    val anchor = p?.anchor ?: Placement.TOP_START
                                    val startW = (p?.width ?: 0f).takeIf { it > 0f }
                                        ?: ((wb?.width ?: 0f) / density)
                                    val startH = (p?.height ?: 0f).takeIf { it > 0f }
                                        ?: ((wb?.height ?: 0f) / density)
                                    val startX = p?.x ?: 0f
                                    val startY = p?.y ?: 0f
                                    var accX = 0f
                                    var accY = 0f
                                    drag(down.id) { change ->
                                        accX += change.positionChange().x
                                        accY += change.positionChange().y
                                        if (geo != null) {
                                            // One gesture, two units: a lattice slot sizes in
                                            // whole cells, so the same drag quantises instead
                                            // of writing a dp extent.
                                            val (cw, ch) = gridResizeSpan(
                                                (p?.width ?: 1f).toInt().coerceAtLeast(1),
                                                (p?.height ?: 1f).toInt().coerceAtLeast(1),
                                                accX, accY, density,
                                                geo.cellDp, geo.gutterDp, geo.columns,
                                            )
                                            editController.resizeWidgetInGrid(path, instance.instanceId, cw, ch, geo.columns)
                                        } else {
                                            val slot = liveSlotSize.value
                                            val r = canvasResize(
                                                edge      = edge,
                                                startXDp  = startX,
                                                startYDp  = startY,
                                                startWDp  = startW,
                                                startHDp  = startH,
                                                accumXPx  = accX,
                                                accumYPx  = accY,
                                                density   = density,
                                                slotWDp   = slot.width,
                                                slotHDp   = slot.height,
                                                hBias     = anchorHorizontalBias(anchor),
                                                vBias     = anchorVerticalBias(anchor),
                                                widthBounds  = widthBounds,
                                                heightBounds = heightBounds,
                                            )
                                            // One write, so the offset and the size cannot
                                            // land a frame apart and the history sees one step.
                                            editController.setWidgetBounds(
                                                path, instance.instanceId, r.x, r.y, r.w, r.h,
                                            )
                                            registry.publishOverlap(
                                                widgetWindowBounds
                                                    ?.let { registry.overlapping(path, it, instance.instanceId) }
                                                    .orEmpty(),
                                            )
                                        }
                                        change.consume()
                                    }
                                    resizing = false
                                    registry.publishOverlap(emptySet())
                                }
                            },
                    ) {
                        // Only a corner carries the glyph. On a side strip six points
                        // wide it would be a smudge, and the strip's own shape already
                        // says which way it pulls.
                        if (edge.isCorner()) {
                            Symbol(icon = NxIcon.OpenInFull,
                                contentDescription = null,
                                tint               = NxTheme.colors.onPrimary,
                                modifier           = Modifier.size(10.dp).padding(0.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    when {
        // Canvas: the widget is positioned by SlotRenderer's outer offset Box.
        // No flow wrapper and no drop bars -- insertion index is meaningless
        // under free placement.
        isPlaced -> widgetBox()
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
    // drag or no drag.
    menuAnchor?.let { anchor ->
        NxContextMenu(anchorInWindow = anchor, expanded = true, onDismissRequest = { menuAnchor = null }) {
            WidgetContextMenuContent(
                isPlaced       = isPlaced,
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
// buttons): configure, then in a placement slot the corner it hangs from and its
// layer, then remove or force-remove. Reads the graph live for the z bounds and
// for the current anchor; each item closes the menu.
@Composable
private fun WidgetContextMenuContent(
    isPlaced: Boolean,
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
    if (isPlaced) {
        // The corner an offset is measured from. It is the whole reason a widget
        // parked at the bottom right survives a window that grows, and until now
        // the only way to set it was to edit the layout file by hand.
        val current = parseAnchor(
            graph.traverse(path)?.widgets?.firstOrNull { it.instanceId == instanceId }?.placement?.anchor
                ?: Placement.TOP_START,
        )
        Text(
            text     = s.editorAnchorTitle,
            style    = MaterialTheme.typography.labelSmall,
            color    = NxTheme.colors.textSecondary,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 2.dp),
        )
        AnchorGrid(current) { editController.setWidgetAnchor(path, instanceId, it); onClose() }
        NxMenuItem(s.editorToFront) {
            val maxZ = graph.traverse(path)?.widgets?.maxOfOrNull { it.placement?.z ?: 0 } ?: 0
            editController.setWidgetZ(path, instanceId, maxZ + 1); onClose()
        }
        NxMenuItem(s.editorToBack) {
            val minZ = graph.traverse(path)?.widgets?.minOfOrNull { it.placement?.z ?: 0 } ?: 0
            editController.setWidgetZ(path, instanceId, minZ - 1); onClose()
        }
    }
    if (removable) NxMenuItem(s.editorDelete) { onRemove() }
    else NxMenuItem(s.editorForceRemove) { onForceRemove() }
}

/**
 * The nine corners, as nine corners.
 *
 * They were nine rows, which put thirteen items in one context menu and pushed it
 * off the bottom of the screen. A grid is also the shape of the thing being
 * chosen: the cell you press is where the widget goes, so the position carries
 * the meaning and the names are left to carry it for a screen reader.
 */
@Composable
private fun AnchorGrid(current: String, onPick: (String) -> Unit) {
    val s = LocalStrings.current
    Column(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Placement.ANCHORS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { anchor ->
                    val selected = anchor == current
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selected) NxTheme.colors.primary.copy(alpha = 0.22f)
                                else NxTheme.colors.surfaceVariant.copy(alpha = 0.5f),
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) NxTheme.colors.primary
                                else NxTheme.colors.outline.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { onPick(anchor) }
                            .semantics { contentDescription = anchorLabel(anchor, s) },
                    )
                }
            }
        }
    }
}

private fun anchorLabel(anchor: String, s: AppStrings): String = when (anchor) {
    Placement.TOP_START -> s.editorAnchorTopStart
    Placement.TOP_CENTER -> s.editorAnchorTopCenter
    Placement.TOP_END -> s.editorAnchorTopEnd
    Placement.CENTER_START -> s.editorAnchorCenterStart
    Placement.CENTER -> s.editorAnchorCenter
    Placement.CENTER_END -> s.editorAnchorCenterEnd
    Placement.BOTTOM_START -> s.editorAnchorBottomStart
    Placement.BOTTOM_CENTER -> s.editorAnchorBottomCenter
    else -> s.editorAnchorBottomEnd
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

// ── Resize handles ───────────────────────────────────────────────────

/** Where on the widget's border a handle for this edge sits. */
private fun ResizeEdge.alignment(): Alignment = when (this) {
    ResizeEdge.North     -> Alignment.TopCenter
    ResizeEdge.South     -> Alignment.BottomCenter
    ResizeEdge.West      -> Alignment.CenterStart
    ResizeEdge.East      -> Alignment.CenterEnd
    ResizeEdge.NorthWest -> Alignment.TopStart
    ResizeEdge.NorthEast -> Alignment.TopEnd
    ResizeEdge.SouthWest -> Alignment.BottomStart
    ResizeEdge.SouthEast -> Alignment.BottomEnd
}

/** True for a handle that moves both axes at once. */
private fun ResizeEdge.isCorner(): Boolean = h != 0 && v != 0

/**
 * A corner is a square and a side is a strip lying along the edge it pulls, so
 * the shape says which way it moves before the cursor does.
 */
private fun ResizeEdge.handleSize(): DpSize = when {
    isCorner() -> DpSize(14.dp, 14.dp)
    h != 0     -> DpSize(6.dp, 24.dp)
    else       -> DpSize(24.dp, 6.dp)
}

/** The system cursor that names this edge while the pointer is over its handle. */
private fun ResizeEdge.cursor(): Int = when (this) {
    ResizeEdge.North     -> Cursor.N_RESIZE_CURSOR
    ResizeEdge.South     -> Cursor.S_RESIZE_CURSOR
    ResizeEdge.West      -> Cursor.W_RESIZE_CURSOR
    ResizeEdge.East      -> Cursor.E_RESIZE_CURSOR
    ResizeEdge.NorthWest -> Cursor.NW_RESIZE_CURSOR
    ResizeEdge.NorthEast -> Cursor.NE_RESIZE_CURSOR
    ResizeEdge.SouthWest -> Cursor.SW_RESIZE_CURSOR
    ResizeEdge.SouthEast -> Cursor.SE_RESIZE_CURSOR
}
