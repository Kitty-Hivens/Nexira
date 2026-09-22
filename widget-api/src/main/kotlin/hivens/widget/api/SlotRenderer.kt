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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
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
import hivens.widget.model.SurfaceInsets
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetSizing
import hivens.widget.model.anchorHorizontalBias
import hivens.widget.model.anchorVerticalBias
import hivens.widget.model.clampPlacementAxis
import hivens.widget.model.flowPlacement
import hivens.widget.model.parseAnchor
import hivens.widget.model.traverse

// Renders every widget at the addressed slot. Two entry forms:
//
//   * Top-level: SlotRenderer(surface, slot) -- used by surface
//     composables (NewHomeScreen, LibraryScreen, AppLayout rails, ...).
//     Initialises LocalSlotPath at the surface root, inside whichever
//     family that surface is currently showing.
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
    // The family is resolved here rather than taken from an argument so a surface
    // that switches families does not have to thread the id through every slot it
    // declares, and so a slot declared before families existed keeps meaning the
    // general one without saying so.
    val path = SlotPath(surface, slot, family = activeFamilyOf(surface))
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
                // Outer spacing around the widget, from its placement so a flow
                // widget reserves room the same way a placed one does.
                val pad = Modifier.padding((instance.placement?.padding ?: SurfaceInsets()).asPadding())
                // Precedence lives on the model as flowPlacement(), so the rule is
                // testable without a composition.
                when (val placement = instance.flowPlacement()) {
                    is FlowPlacement.Weighted -> Box(weight(placement.weight).then(pad)) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
                    is FlowPlacement.Bounded -> Box(boundedModifier(placement, descriptor.sizing).then(pad)) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
                    FlowPlacement.Natural -> Box(pad) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
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
                    val pad = Modifier.padding((instance.placement?.padding ?: SurfaceInsets()).asPadding())
                    Box(pad) { decorator(address, index, descriptor, instance) { movable() } }
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
private fun boundedModifier(placement: FlowPlacement.Bounded, sizing: WidgetSizing): Modifier {
    // Held inside what the widget says it can use, so a bound dragged under the
    // content stops at the content instead of cutting it, and one dragged past
    // where the widget stops drawing reserves nothing extra. Undeclared, which is
    // most widgets, leaves the bound exactly as it was written.
    val w = sizing.boundWidth(placement.widthDp)
    val h = sizing.boundHeight(placement.heightDp)
    var m: Modifier = Modifier
    if (w > 0f) m = m.widthIn(max = w.dp)
    if (h > 0f) m = m.heightIn(max = h.dp)
    return m
}

// Outer spacing from a widget's placement, as PaddingValues, so a widget's padding
// reads the same in a flow slot as in a placement one. All zero is the common case
// and draws as no inset.
private fun SurfaceInsets.asPadding(): PaddingValues = PaddingValues(
    start = start(0f).dp,
    top = top(0f).dp,
    end = end(0f).dp,
    bottom = bottom(0f).dp,
)

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
        val boundedHeight = maxHeight.value.takeIf { it.isFinite() } ?: 0f
        // The clamp wants the size the slot took, and takes the constraint until
        // that arrives: measured is only right for a slot that wraps its content,
        // and waiting a frame for it means the frame a widget first appears on is
        // the one frame nothing holds it. Which is the frame a screenshot catches.
        val clampSize = Size(
            if (measuredDp.width > 0f) measuredDp.width else boundedWidth,
            if (measuredDp.height > 0f) measuredDp.height else boundedHeight,
        )
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
                        val sizing = descriptor?.sizing ?: WidgetSizing.UNDECLARED
                        PlacedBox(p, columns, cell, spacing.value, clampSize, sizing) {
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
    slotDp: Size,
    sizing: WidgetSizing,
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

    // What it actually drew, a frame late, which is all a recovery clamp needs:
    // nobody is dragging on the frame a widget first appears.
    val density = LocalDensity.current
    var ownDp by remember { mutableStateOf(Size.Zero) }

    // Held where it can still be grabbed. A free slot stores an offset in dp and
    // nothing ever refused one, so a widget put past the edge was drawn past the
    // edge with nothing left to take hold of, and the only way back was the
    // surface reset or the file. The lattice above clamps for the same reason.
    // The record is untouched, so a slot that grows gives the arrangement back
    // exactly as it was written.
    //
    // How big it is has to be known first, or the clamp reads a widget as having
    // no size and demands the offset itself clear the margin, which shoves every
    // widget near the origin away from it. What it drew is the answer, because
    // that is what somebody has to be able to grab; the placement is only an upper
    // bound on it (see [sizeMod]) and a widget that draws smaller would otherwise
    // be held by the edge of a box nobody can see. The bound stands in for the
    // frame before the first measurement, and a widget that names neither waits,
    // because holding by a guess moves things that were never out of place.
    val ownW = ownDp.width.takeIf { it > 0f } ?: width
    val ownH = ownDp.height.takeIf { it > 0f } ?: height
    // Clamp in offset space, the unit the record and the drag both use, then apply
    // the anchor's inward sign. Passing the already-signed nudge in read an end
    // anchor's inset as a leading offset, which stayed invisible only because the
    // grab-margin range was wide enough on both sides to contain a small value of
    // either sign. Containment is one-sided and would have drawn end and centre
    // anchors in the wrong place.
    val clampedX = if (lattice || ownW <= 0f) offX else clampPlacementAxis(offX, slotDp.width, ownW, hBias)
    val clampedY = if (lattice || ownH <= 0f) offY else clampPlacementAxis(offY, slotDp.height, ownH, vBias)
    val heldX = if (hBias > 0.5f) -clampedX else clampedX
    val heldY = if (vBias > 0.5f) -clampedY else clampedY

    // A placement is a claim on territory, not an order to stretch, so it lands
    // as a MAXIMUM and never as a fixed extent. The flow branch has always read
    // it that way ([boundedModifier]); this one set Modifier.size and so broke
    // the rule at both ends. Too small a claim cut the widget off with nothing
    // saying so: a column player given 272 of the 304 it draws lost its transport
    // and looked like a player with no play button. Too large a claim was worse
    // because it was invisible: a token given 412 reported 412 wide and painted
    // 96, so the editor's frame, the reported size and the space the widget held
    // against its neighbours were all the claim, and none of them was the pixels.
    // Content that fills still fills to the bound; content that does not sits at
    // its own size inside it, which is the widget keeping its look.
    //
    // Each axis on its own: a widget that names a width and not a height is as
    // expressible as one that names both, and requiring the pair silently threw
    // the one away.
    //
    // Held inside what the widget says it can use, which is what finally makes the
    // rule enforceable rather than hoped for. A claim under the widget's own floor
    // is raised to the floor, so the widget is drawn at the size it needs instead
    // of cut down to a claim that removes content. What it does NOT do is spill:
    // the bound IS the box here, so the widget still ends at the floor. In a
    // lattice the cell and the bound are different numbers, and there an occupant
    // larger than its cell does overflow it, which the overlap warning says.
    val boundW = sizing.boundWidth(width)
    val boundH = sizing.boundHeight(height)
    var sizeMod: Modifier = Modifier
    if (boundW > 0f) sizeMod = sizeMod.widthIn(max = boundW.dp)
    if (boundH > 0f) sizeMod = sizeMod.heightIn(max = boundH.dp)
    // Space reserved around the widget, from the placement rather than the plane,
    // so a widget that paints its own plane gets it too. Outside sizeMod and inside
    // onSizeChanged: the plane keeps its claimed size, the padding sits around it,
    // and what the clamp measures (ownDp) is the padded footprint, so the reserved
    // space stays inside the slot the same way the plane does. Never a Modifier.size,
    // so it offsets and reserves rather than shrinking the plane.
    val pad = placement.padding
    Box(
        Modifier
            .align(alignmentFor(anchor))
            .offset(heldX.dp, heldY.dp)
            .onSizeChanged { ownDp = with(density) { Size(it.width.toDp().value, it.height.toDp().value) } }
            .padding(
                PaddingValues(
                    start = pad.start(0f).dp,
                    top = pad.top(0f).dp,
                    end = pad.end(0f).dp,
                    bottom = pad.bottom(0f).dp,
                ),
            )
            .then(sizeMod),
    ) {
        // What was chosen, not what happened to be free. A widget that adapts to
        // its footprint reads this rather than its constraints, which in a slot
        // that fills its surface are the rest of the screen. The plane size, not
        // the padded one: padding is around the widget, not part of it.
        CompositionLocalProvider(LocalWidgetFootprintDp provides Size(width, height)) {
            content()
        }
    }
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
    // Published around the body so a widget can read its own declaration without
    // being handed its descriptor. AdaptiveWidget takes its reference size from
    // here, which is what keeps the number the annotation carries and the number
    // the widget draws at from being two numbers.
    CompositionLocalProvider(LocalWidgetSizing provides descriptor.sizing) {
        val body = @Composable {
            if (surface == null) {
                descriptor.Render(instance)
            } else {
                LocalWidgetSurfaceRenderer.current(surface) { descriptor.Render(instance) }
            }
        }
        // One funnel, so every widget in every branch is covered once. The node is
        // added only for a widget that declared a ceiling, so nothing an
        // undeclared widget sees changes: most of the registry declares nothing
        // and none of it should start measuring differently for this.
        val ceiling = descriptor.sizing.unboundedAxisCeiling()
        if (ceiling == null) body() else Box(ceiling) { body() }
    }
}

/**
 * Substitutes the widget's declared maximum for an axis it was given no bound on,
 * or null when it declared no maximum to substitute.
 *
 * An unbounded axis is not a generous offer, it is the absence of an answer, and
 * a widget that scrolls or lazily lists cannot be measured against one: Compose
 * throws rather than guessing. That is reachable from the editor, because the
 * editor lets any widget be dropped in any slot and a slot inherits whatever its
 * surface hands down. Two surfaces already avoid it by not scrolling around a
 * slot, each with a comment saying so, which is a rule kept by hand in the places
 * that happened to be written carefully.
 *
 * The declaration is the place that already answers "how much can this use", so
 * it answers here too. Nothing is imposed where a bound exists: a slot that said
 * a height is obeyed, including one that said less than the widget wants, and the
 * claim rules in [boundedModifier] are untouched. This only fills a silence.
 *
 * A zero maximum means undeclared, and an undeclared widget in an unbounded slot
 * is left exactly as it was, which is to say it still throws. That is deliberate:
 * inventing a ceiling for it would be this file guessing at a widget's size, and
 * the fix for those is the declaration they are missing.
 */
private fun WidgetSizing.unboundedAxisCeiling(): Modifier? {
    if (maxWidth <= 0 && maxHeight <= 0) return null
    val ceilingW = maxWidth
    val ceilingH = maxHeight
    return Modifier.layout { measurable, constraints ->
        val filled = Constraints(
            minWidth = constraints.minWidth,
            // coerceAtLeast the minimum: a Constraints with max below min does not
            // exist, and an unbounded axis can still carry a minimum.
            maxWidth = if (ceilingW > 0 && constraints.maxWidth == Constraints.Infinity) {
                ceilingW.dp.roundToPx().coerceAtLeast(constraints.minWidth)
            } else {
                constraints.maxWidth
            },
            minHeight = constraints.minHeight,
            maxHeight = if (ceilingH > 0 && constraints.maxHeight == Constraints.Infinity) {
                ceilingH.dp.roundToPx().coerceAtLeast(constraints.minHeight)
            } else {
                constraints.maxHeight
            },
        )
        val placeable = measurable.measure(filled)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
