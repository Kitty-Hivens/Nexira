package hivens.widget.api

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.widget.model.FlowPlacement
import hivens.widget.model.FlowSpec
import hivens.widget.model.GRID_MAX
import hivens.widget.model.Placement
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.anchorHorizontalBias
import hivens.widget.model.anchorVerticalBias
import hivens.widget.model.flowPlacement
import hivens.widget.model.parseAnchor
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
// The slot OWNS its intra-slot layout, and owns it in one of two modes rather
// than one of five names. A flow derives each child's position from the
// sequence; a placement slot reads it off the child. The surface passes
// `modifier` for inter-slot positioning (weight / fill / padding / scroll) and
// `spacing` for the gap between children, which in a lattice is the gutter.
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

    val flow = content.flow
    if (flow == null) {
        PlacementSlot(path, content, address, registry, decorator, unknownDecorator, slotChrome, modifier, spacing)
    } else {
        FlowSlot(flow, content, address, registry, decorator, unknownDecorator, slotChrome(path, content), modifier, spacing, motionMs)
    }
}

// ── Flow ─────────────────────────────────────────────────────────────

// One body for what used to be three branches. Row and Column differ only in
// which axis a weighted child takes its share of, and could not share a body
// only because Modifier.weight is scope-typed -- so the layout passes its own
// weight in and everything else is written once. Wrapping is the same flow with
// a line length, which is what the grid always was.
@Composable
private fun FlowSlot(
    flow: FlowSpec,
    content: SlotContent,
    address: SlotAddress,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    chrome: Modifier,
    modifier: Modifier,
    spacing: Dp,
    motionMs: Int,
) {
    val outer = chrome.then(modifier).animatedReflow(motionMs)
    val lineLength = flow.wrap.coerceAtLeast(0)

    if (lineLength == 0) {
        if (flow.horizontal) {
            Row(outer, horizontalArrangement = Arrangement.spacedBy(spacing)) {
                FlowWidgets(address, content.widgets, registry, decorator, unknownDecorator) { Modifier.weight(it) }
            }
        } else {
            Column(outer, verticalArrangement = Arrangement.spacedBy(spacing)) {
                FlowWidgets(address, content.widgets, registry, decorator, unknownDecorator) { Modifier.weight(it) }
            }
        }
        return
    }

    // Wrapped: lines of `wrap` children, laid across the flow direction and
    // stacked along the other one. A uniform line gives every cell an equal
    // share and pads the short last line with weighted spacers so the columns
    // stay aligned; a non-uniform one lets each child take its own size.
    val lines = content.widgets.chunked(lineLength)
    if (flow.horizontal) {
        Column(outer, verticalArrangement = Arrangement.spacedBy(spacing)) {
            lines.forEachIndexed { lineIndex, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    WrappedLine(address, line, lineIndex, lineLength, flow.uniform, registry, decorator, unknownDecorator) {
                        Modifier.weight(it)
                    }
                    if (flow.uniform) repeat(lineLength - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        Row(outer, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            lines.forEachIndexed { lineIndex, line ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    WrappedLine(address, line, lineIndex, lineLength, flow.uniform, registry, decorator, unknownDecorator) {
                        Modifier.weight(it)
                    }
                    if (flow.uniform) repeat(lineLength - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FlowWidgets(
    address: SlotAddress,
    widgets: List<WidgetInstance>,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    weight: (Float) -> Modifier,
) {
    widgets.forEachIndexed { index, instance ->
        key(instance.instanceId) {
            val descriptor = registry[instance.kind]
            if (descriptor == null) {
                unknownDecorator(address, index, instance)
            } else {
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
            }
        }
    }
}

// One line of a wrapped flow. `index` has to be the child's position in the
// whole slot, not in the line, because that is what the drop hit-test and the
// decorator address it by.
@Composable
private fun WrappedLine(
    address: SlotAddress,
    line: List<WidgetInstance>,
    lineIndex: Int,
    lineLength: Int,
    uniform: Boolean,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    weight: (Float) -> Modifier,
) {
    line.forEachIndexed { inLine, instance ->
        val index = lineIndex * lineLength + inLine
        key(instance.instanceId) {
            val descriptor = registry[instance.kind]
            val cell: Modifier = if (uniform) weight(1f) else Modifier
            Box(cell) {
                if (descriptor == null) {
                    unknownDecorator(address, index, instance)
                } else {
                    val movable = rememberWidgetMovable(descriptor, instance)
                    decorator(address, index, descriptor, instance) { movable() }
                }
            }
        }
    }
}

// Per-widget resize for a flow slot, applied as a MAXIMUM bound. Content that
// fills (a list, an image) grows to the bound; content that does not (a card, a
// label, a spacer at its prop height) wraps at its natural size instead of
// leaving empty space below or beside it -- so dragging the handle past the
// content no longer inflates the box with phantom padding. A fixed extent only
// suits a placement slot, which sets Modifier.size directly.
private fun boundedModifier(placement: FlowPlacement.Bounded): Modifier {
    var m: Modifier = Modifier
    if (placement.widthDp > 0f) m = m.widthIn(max = placement.widthDp.dp)
    if (placement.heightDp > 0f) m = m.heightIn(max = placement.heightDp.dp)
    return m
}

// ── Placement ────────────────────────────────────────────────────────

// Free and lattice placement are one branch, because a lattice is free
// placement whose unit happens to be a cell rather than a dp. The slot's `grid`
// says which: 0 measures in dp, N measures in cells of an N-column lattice
// whose cell size comes from the measured width, so a position survives a
// window resize instead of being clipped on a narrow one.
@Composable
private fun PlacementSlot(
    path: SlotPath,
    content: SlotContent,
    address: SlotAddress,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    slotChrome: SlotChromeModifier,
    modifier: Modifier,
    spacing: Dp,
) {
    val density = LocalDensity.current
    val reportSlotBounds = LocalSlotBoundsReporter.current
    val columns = content.grid.coerceIn(0, GRID_MAX)

    // Two measurements, for two jobs, and they are not the same number.
    //
    // The cell comes from the constraints, during composition. A lattice turns a
    // cell address into dp against the width, and a width that only arrives after
    // the first layout means the first frame draws every widget at the origin at
    // full size. In the running app that is one wrong frame; anywhere a single
    // frame is the whole answer, it is the answer.
    //
    // The clamp that keeps a dragged widget reachable needs the size the slot
    // actually took, which is not the constraint's maximum in a slot that wraps
    // its content, and which is infinite on an unbounded axis. It is allowed to
    // arrive a frame late, because nobody is dragging on the frame a slot first
    // appears, and publishing a zero would switch the clamp off entirely.
    var measuredDp by remember { mutableStateOf(Size.Zero) }
    BoxWithConstraints(
        slotChrome(path, content)
            .then(modifier)
            .onSizeChanged { sz ->
                measuredDp = with(density) { Size(sz.width.toDp().value, sz.height.toDp().value) }
            }
            .onGloballyPositioned { reportSlotBounds(path, it.boundsInWindow()) },
    ) {
        val boundedWidth = maxWidth.value.takeIf { it.isFinite() } ?: 0f
        // One cell, gutters taken off first. Zero outside a lattice, and zero in a
        // slot with no bounded width, where a fraction of the width has nothing to
        // be a fraction of.
        val cell: Float = if (columns > 0 && boundedWidth > 0f) {
            ((boundedWidth - spacing.value * (columns + 1)) / columns).coerceAtLeast(0f)
        } else {
            0f
        }

        CompositionLocalProvider(
            LocalPlacementSlotSizeDp provides measuredDp,
            // Published only when there is a cell to convert against. A geometry
            // carrying a zero cell reads as a lattice to the editor and then
            // answers every pointer delta with "no movement", which is a gesture
            // that is present and does nothing.
            LocalGridGeometry provides if (columns > 0 && cell > 0f) GridGeometry(cell, spacing.value, columns) else null,
        ) {
            content.widgets.withIndex()
                .sortedWith(compareBy({ it.value.placement?.z ?: 0 }, { it.index }))
                .forEach { (index, instance) ->
                    key(instance.instanceId) {
                        val p = instance.placement ?: Placement()
                        val descriptor = registry[instance.kind]
                        PlacedBox(p, columns, cell, spacing.value) {
                            if (descriptor == null) {
                                unknownDecorator(address, index, instance)
                            } else {
                                val movable = rememberWidgetMovable(descriptor, instance)
                                decorator(address, index, descriptor, instance) { movable() }
                            }
                        }
                    }
                }
        }
    }
}

// Positions one child against its anchor. Compose's own alignment does the bias
// arithmetic, so the offset is only the nudge away from that corner -- and it
// runs inward from an end anchor, because "16 from the right" is what somebody
// parking a widget in a corner means, not "16 further right than the edge".
@Composable
private fun BoxScope.PlacedBox(
    placement: Placement,
    columns: Int,
    cell: Float,
    gutter: Float,
    content: @Composable () -> Unit,
) {
    val lattice = columns > 0
    val stride = cell + gutter

    // A lattice clamps what it is given, the way the cube grid it replaces did.
    // Nothing keeps a stored position inside a lattice that has since been made
    // narrower: the count is a number in a menu, and reducing it used to leave a
    // widget parked at a column that no longer exists, drawn past the slot, out
    // of the window and out of reach, with no way back but to raise the count
    // again. The record is left alone and the drawing is clamped, so lowering the
    // count is reversible.
    val spanW = if (lattice) placement.width.coerceIn(1f, columns.toFloat()) else placement.width
    val spanH = if (lattice) placement.height.coerceAtLeast(1f) else placement.height
    val col = if (lattice) placement.x.coerceIn(0f, (columns - spanW).coerceAtLeast(0f)) else placement.x
    val row = if (lattice) placement.y.coerceAtLeast(0f) else placement.y

    val offX = if (lattice) gutter + col * stride else col
    val offY = if (lattice) gutter + row * stride else row
    val width = if (lattice) spanW * stride - gutter else placement.width
    val height = if (lattice) spanH * stride - gutter else placement.height

    val anchor = parseAnchor(placement.anchor)
    val hBias = anchorHorizontalBias(anchor)
    val vBias = anchorVerticalBias(anchor)
    val dx = if (hBias > 0.5f) -offX else offX
    val dy = if (vBias > 0.5f) -offY else offY

    // Each axis on its own: a widget that names a width and not a height is as
    // expressible as one that names both, and requiring the pair silently threw
    // the one away.
    var sizeMod: Modifier = Modifier
    if (width > 0f) sizeMod = sizeMod.width(width.dp)
    if (height > 0f) sizeMod = sizeMod.height(height.dp)
    Box(Modifier.align(alignmentFor(anchor)).offset(dx.dp, dy.dp).then(sizeMod)) { content() }
}

private fun alignmentFor(anchor: String): Alignment = when (anchor) {
    Placement.TOP_START -> Alignment.TopStart
    Placement.TOP_CENTER -> Alignment.TopCenter
    Placement.TOP_END -> Alignment.TopEnd
    Placement.CENTER_START -> Alignment.CenterStart
    Placement.CENTER -> Alignment.Center
    Placement.CENTER_END -> Alignment.CenterEnd
    Placement.BOTTOM_START -> Alignment.BottomStart
    Placement.BOTTOM_CENTER -> Alignment.BottomCenter
    else -> Alignment.BottomEnd
}

// ── Shared ───────────────────────────────────────────────────────────

// Compose forbids try/catch around a @Composable invocation (compiler error),
// and there is no public per-subtree error boundary, so a single widget's
// failure can't be isolated here -- crash recovery is the shell remount
// (UiRecoverySignal) at the composition root, not a per-widget catch.
// Edit-mode reflow: animate the slot container's footprint as widgets are
// added / removed / resized so the change reads as motion, not a jump. motionMs
// 0, the production default, returns the modifier untouched at zero cost.
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
    val instanceState = rememberUpdatedState(instance)
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
