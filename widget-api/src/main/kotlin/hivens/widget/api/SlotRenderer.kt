package hivens.widget.api

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.widget.model.FlowPlacement
import hivens.widget.model.GridCell
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotOrientation
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.flowPlacement
import hivens.widget.model.traverse

// Renders every widget at the addressed slot. Two entry forms:
//
//   * Top-level: SlotRenderer(surface, slot) -- used by surface
//     composables (NewHomeScreen, LibraryScreen, AppLayout rails, ...).
//     Initialises LocalSlotPath at the surface root.
//
//   * Nested: SlotRenderer(parent, slot) -- used by container widgets
//     inside their @Composable body. Extends LocalSlotPath with the
//     container's instanceId so the editor's drop-target registry
//     distinguishes "slot 'body' on container X" from "slot 'body' on
//     container Y".
//
// Phase G: the slot OWNS its intra-slot layout. The renderer reads the
// slot's SlotOrientation and lays the widgets out itself (Column / Row;
// Grid renders as Column until G5), so callers no longer wrap the call in
// a Column. The surface passes `modifier` for inter-slot positioning
// (weight / fill / padding / scroll) and `spacing` for the gap between
// widgets (the arrangement the caller's Column used to set). A widget
// with weight > 0 takes a weighted share of the main axis; weight 0
// renders through the decorator directly, exactly as before.
//
// Each widget renders through LocalWidgetDecorator. Default decorator is
// identity -- zero cost when no editor is mounted. A widget whose kind is
// absent from the registry renders through LocalUnknownWidgetDecorator
// instead (default nothing in production; the editor paints an "unsupported
// widget" placeholder so the orphan is visible and removable), and keeps
// its on-disk props / children intact.
@Composable
fun SlotRenderer(
    surface: SurfaceId,
    slot: SlotId,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
) {
    val path = SlotPath(surface, slot)
    CompositionLocalProvider(LocalSlotPath provides path) {
        RenderSlotContent(path, modifier, spacing)
    }
}

@Composable
fun SlotRenderer(
    parent: WidgetInstance,
    slot: SlotId,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
) {
    val parentPath = LocalSlotPath.current
    val childPath = parentPath.child(parent.instanceId, slot)
    CompositionLocalProvider(LocalSlotPath provides childPath) {
        RenderSlotContent(childPath, modifier, spacing)
    }
}

@Composable
private fun RenderSlotContent(path: SlotPath, modifier: Modifier, spacing: Dp) {
    val graph = LocalLayoutGraph.current
    val registry = LocalWidgetRegistry.current
    val decorator = LocalWidgetDecorator.current
    val emptyDecorator = LocalEmptySlotDecorator.current
    val unknownDecorator = LocalUnknownWidgetDecorator.current
    val slotChrome = LocalSlotChromeModifier.current
    val motionMs = LocalSlotMotionMs.current

    val content: SlotContent = graph.traverse(path) ?: SlotContent()
    val address = path.leafAddress

    if (content.widgets.isEmpty()) {
        // Occupy the slot footprint (inter-slot sizing lives in `modifier`)
        // so an empty slot keeps its place; the empty decorator paints the
        // edit-mode placeholder, or nothing.
        Box(slotChrome(path, content).then(modifier)) { emptyDecorator(address) }
        return
    }

    when (content.orientation) {
        SlotOrientation.Row -> Row(slotChrome(path, content).then(modifier).animatedReflow(motionMs), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            FlowWidgets(address, content, registry, decorator, unknownDecorator) { Modifier.weight(it) }
        }
        SlotOrientation.Grid -> Column(slotChrome(path, content).then(modifier).animatedReflow(motionMs), verticalArrangement = Arrangement.spacedBy(spacing)) {
            // Non-lazy chunked grid: a Column of equal-width Rows. Reuses the
            // decorator path so chrome + DnD stay consistent; cells are uniform
            // (per-widget weight is ignored in Grid) and the last row is padded
            // with weighted spacers so the columns stay aligned. No dividers.
            val cols = content.gridColumns.coerceAtLeast(1)
            content.widgets.chunked(cols).forEachIndexed { rowIndex, rowWidgets ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    rowWidgets.forEachIndexed { colIndex, instance ->
                        val index = rowIndex * cols + colIndex
                        key(instance.instanceId) {
                            val descriptor = registry[instance.kind]
                            Box(Modifier.weight(1f)) {
                                if (descriptor != null) {
                                    val movable = rememberWidgetMovable(descriptor, instance)
                                    decorator(address, index, descriptor, instance) { movable() }
                                } else {
                                    unknownDecorator(address, index, instance)
                                }
                            }
                        }
                    }
                    repeat(cols - rowWidgets.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        SlotOrientation.Canvas -> {
            // Publish the slot's measured size (dp) so the editor's move gesture
            // clamps a free-placed widget on-canvas. onSizeChanged keeps it
            // current without restarting the gesture.
            val density = LocalDensity.current
            val reportSlotBounds = LocalSlotBoundsReporter.current
            var slotSizeDp by remember { mutableStateOf(Size.Zero) }
            Box(
                slotChrome(path, content)
                    .then(modifier)
                    .onSizeChanged { sz ->
                        slotSizeDp = with(density) { Size(sz.width.toDp().value, sz.height.toDp().value) }
                    }
                    .onGloballyPositioned { reportSlotBounds(path, it.boundsInWindow()) },
            ) {
                CompositionLocalProvider(LocalCanvasSlotSizeDp provides slotSizeDp) {
                    // Free canvas: each widget at its absolute dp offset + size,
                    // painted in z-order then index. Overlap is natural in a Box.
                    content.widgets.withIndex()
                        .sortedWith(compareBy({ it.value.canvas?.z ?: 0 }, { it.index }))
                        .forEach { (index, instance) ->
                            key(instance.instanceId) {
                                val cp = instance.canvas
                                val descriptor = registry[instance.kind]
                                if (descriptor == null) {
                                    Box(Modifier.offset((cp?.x ?: 0f).dp, (cp?.y ?: 0f).dp)) {
                                        unknownDecorator(address, index, instance)
                                    }
                                } else {
                                    val movable = rememberWidgetMovable(descriptor, instance)
                                    val sizeMod = if (cp != null && cp.width > 0f && cp.height > 0f)
                                        Modifier.size(cp.width.dp, cp.height.dp) else Modifier
                                    Box(Modifier.offset((cp?.x ?: 0f).dp, (cp?.y ?: 0f).dp).then(sizeMod)) {
                                        decorator(address, index, descriptor, instance) { movable() }
                                    }
                                }
                            }
                        }
                }
            }
        }
        SlotOrientation.CubeGrid -> {
            // Cube-cell grid: each widget occupies an addressed cell rectangle
            // (col/row + span). Cell size derives from the measured slot width and
            // the column count; cells are square (cellH = cellW); `spacing` is the
            // gutter. Static placement here -- live move/resize land in later phases.
            val density = LocalDensity.current
            val reportSlotBounds = LocalSlotBoundsReporter.current
            var slotSizeDp by remember { mutableStateOf(Size.Zero) }
            val cols = content.gridColumns.coerceAtLeast(1)
            Box(
                slotChrome(path, content)
                    .then(modifier)
                    .onSizeChanged { sz ->
                        slotSizeDp = with(density) { Size(sz.width.toDp().value, sz.height.toDp().value) }
                    }
                    .onGloballyPositioned { reportSlotBounds(path, it.boundsInWindow()) },
            ) {
                val cellW: Dp =
                    if (slotSizeDp.width > 0f) ((slotSizeDp.width.dp - spacing * (cols + 1)) / cols).coerceAtLeast(0.dp)
                    else 0.dp
                CompositionLocalProvider(
                    LocalCanvasSlotSizeDp provides slotSizeDp,
                    LocalCubeGeometry provides CubeGeometry(cellW.value, spacing.value, cols),
                ) {
                    content.widgets.withIndex()
                        .sortedWith(compareBy({ it.value.cell?.z ?: 0 }, { it.index }))
                        .forEach { (index, instance) ->
                            key(instance.instanceId) {
                                val gc      = instance.cell ?: GridCell()
                                val colSpan = gc.colSpan.coerceIn(1, cols)
                                val col     = gc.col.coerceIn(0, cols - colSpan)
                                val rowSpan = gc.rowSpan.coerceAtLeast(1)
                                val row     = gc.row.coerceAtLeast(0)
                                val x = spacing + (cellW + spacing) * col
                                val y = spacing + (cellW + spacing) * row
                                val w = cellW * colSpan + spacing * (colSpan - 1)
                                val h = cellW * rowSpan + spacing * (rowSpan - 1)
                                val descriptor = registry[instance.kind]
                                Box(Modifier.offset(x, y).size(w, h)) {
                                    if (descriptor != null) {
                                        val movable = rememberWidgetMovable(descriptor, instance)
                                        decorator(address, index, descriptor, instance) { movable() }
                                    } else {
                                        unknownDecorator(address, index, instance)
                                    }
                                }
                            }
                        }
                }
            }
        }
        // Column.
        else -> Column(slotChrome(path, content).then(modifier).animatedReflow(motionMs), verticalArrangement = Arrangement.spacedBy(spacing)) {
            FlowWidgets(address, content, registry, decorator, unknownDecorator) { Modifier.weight(it) }
        }
    }
}

// The Row and Column branches differ in exactly one thing: which axis a weighted
// widget takes its share of. Modifier.weight is scope-typed, so the two cannot
// share a body by one calling the other -- the layout passes its own weight in
// instead, and the rest (placement precedence, the decorator, the unknown-kind
// fallback) is written once. It was written twice, line for line, and the comment
// on both copies said they must not drift.
@Composable
private fun FlowWidgets(
    address: SlotAddress,
    content: SlotContent,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    weight: (Float) -> Modifier,
) {
    content.widgets.forEachIndexed { index, instance ->
        key(instance.instanceId) {
            val descriptor = registry[instance.kind]
            if (descriptor != null) {
                val movable = rememberWidgetMovable(descriptor, instance)
                // Precedence lives on the model as flowPlacement(), so the rule is
                // testable without a composition.
                when (val placement = instance.flowPlacement()) {
                    is FlowPlacement.Weighted -> Box(weight(placement.weight)) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
                    is FlowPlacement.Bounded -> Box(boundedModifier(placement)) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
                    FlowPlacement.Natural -> decorator(address, index, descriptor, instance) { movable() }
                }
            } else {
                unknownDecorator(address, index, instance)
            }
        }
    }
}

// Per-widget resize for a flow (Row/Column) slot, applied as a MAXIMUM bound, or
// null when the widget has no canvas size set. Content that fills (a list, an
// image) grows to the bound; content that does not (a card, a label, a spacer at
// its prop height) wraps at its natural size instead of leaving empty space below
// or beside it -- so dragging the handle past the content no longer inflates the
// box with phantom padding. A fixed extent only suits the free canvas (which sets
// Modifier.size directly). Only a resized widget carries a size, so untouched
// layouts are unaffected.
private fun boundedModifier(placement: FlowPlacement.Bounded): Modifier {
    var m: Modifier = Modifier
    if (placement.widthDp > 0f) m = m.widthIn(max = placement.widthDp.dp)
    if (placement.heightDp > 0f) m = m.heightIn(max = placement.heightDp.dp)
    return m
}

// Compose forbids try/catch around a @Composable invocation (compiler error),
// and there is no public per-subtree error boundary, so a single widget's
// failure can't be isolated here -- crash recovery is the shell remount
// (UiRecoverySignal) at the composition root, not a per-widget catch.
// Edit-mode reflow: animate the slot container's footprint as widgets are
// added / removed / resized so the change reads as motion, not a jump. motionMs
// 0 (production, and Brut) returns the modifier untouched -- zero cost.
private fun Modifier.animatedReflow(motionMs: Int): Modifier =
    if (motionMs > 0) this.then(Modifier.animateContentSize(tween(motionMs))) else this

// Wraps a widget's content in a per-instance movableContentOf so the editor's
// identity<->chrome decorator swap (a static-local change that relocates content()
// deeper in the slot tree) MOVES the widget subtree instead of disposing it -- the
// widget keeps its loaded state (remember / LaunchedEffect) across an edit-mode
// toggle. rememberUpdatedState feeds the latest descriptor/instance so a prop edit
// does not force the movable to be recreated. Call inside a key(instanceId) so the
// movable is per-instance: stable across reorder, cleaned up when the instance leaves.
@Composable
private fun rememberWidgetMovable(descriptor: WidgetDescriptor, instance: WidgetInstance): @Composable () -> Unit {
    val descriptorState = rememberUpdatedState(descriptor)
    val instanceState   = rememberUpdatedState(instance)
    return remember { movableContentOf { RenderWidget(descriptorState.value, instanceState.value) } }
}

// Renders a widget, wrapped in the plane it resolves to. The wrap is inside the
// editor decorator (the drag handle and remove button surround the plane) but is
// PRODUCTION styling -- it paints whether the editor is mounted or not.
//
// Which plane it draws is [resolveSurface]'s answer, so the renderer and the
// editor's panel read the same one.
@Composable
private fun RenderWidget(descriptor: WidgetDescriptor, instance: WidgetInstance) {
    val surface = descriptor.resolveSurface(instance)
    if (surface == null) {
        descriptor.Render(instance)
    } else {
        LocalWidgetSurfaceRenderer.current(surface) { descriptor.Render(instance) }
    }
}
