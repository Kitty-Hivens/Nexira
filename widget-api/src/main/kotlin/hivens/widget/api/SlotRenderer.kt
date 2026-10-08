package hivens.widget.api

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
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
import hivens.widget.model.ViewportMode
import hivens.widget.model.WidgetSizing
import hivens.widget.model.anchorHorizontalBias
import hivens.widget.model.anchorVerticalBias
import hivens.widget.model.clampPlacementAxis
import hivens.widget.model.flowPlacement
import hivens.widget.model.parseAnchor
import hivens.widget.model.traverse
import hivens.widget.model.viewportMode

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
// `modifier` for inter-slot positioning (weight / fill / padding), `spacing` for
// the gap between children, which in a lattice is the gutter, and
// `contentPadding` for room inside the slot that the content scrolls through.
//
// Whether the slot scrolls is the slot's own record (SlotContent.viewport), not
// the surface's, so a surface never wraps a slot in a scroll of its own: the
// kernel is what knows how a widget has to be measured once an axis is unbounded.
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
    contentPadding: PaddingValues = NO_PADDING,
) {
    // The family is resolved here rather than taken from an argument so a surface
    // that switches families does not have to thread the id through every slot it
    // declares, and so a slot declared before families existed keeps meaning the
    // general one without saying so.
    val path = SlotPath(surface, slot, family = activeFamilyOf(surface))
    val mounted = LocalMountedSurfaces.current
    // Open above already: this slot is inside its own surface, through some widget
    // that opens it. Refused rather than drawn, because drawing it draws it again.
    // It keeps the slot's footprint, the way an empty slot does, so the layout
    // around it does not jump because one of its slots refused.
    if (surface in mounted) {
        val refused = LocalRefusedMount.current
        Box(modifier) { refused(surface) }
        return
    }
    val nowMounted = remember(mounted, surface) { mounted + surface }
    CompositionLocalProvider(LocalSlotPath provides path, LocalMountedSurfaces provides nowMounted) {
        RenderSlotContent(path, modifier, spacing, contentPadding)
    }
}

@Composable
fun SlotRenderer(
    parent: WidgetInstance,
    slot: SlotId,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    contentPadding: PaddingValues = NO_PADDING,
) {
    val parentPath = LocalSlotPath.current
    val childPath = parentPath.child(parent.instanceId, slot)
    CompositionLocalProvider(LocalSlotPath provides childPath) {
        RenderSlotContent(childPath, modifier, spacing, contentPadding)
    }
}

private val NO_PADDING = PaddingValues(0.dp)

// Padding only when there is some, so the slots that ask for none, which is nearly
// all of them, do not each carry a node that does nothing.
private fun Modifier.inset(padding: PaddingValues): Modifier =
    if (padding == NO_PADDING) this else this.padding(padding)

@Composable
private fun RenderSlotContent(path: SlotPath, modifier: Modifier, spacing: Dp, contentPadding: PaddingValues) {
    val graph = LocalLayoutGraph.current
    val slotChrome = LocalSlotChromeModifier.current

    val content: SlotContent = graph.traverse(path) ?: SlotContent()
    val chrome = slotChrome(path, content)

    if (content.widgets.isEmpty()) {
        // Occupy the slot footprint (inter-slot sizing lives in `modifier`)
        // so an empty slot keeps its place; the empty decorator paints the
        // edit-mode placeholder, or nothing.
        val emptyDecorator = LocalEmptySlotDecorator.current
        Box(chrome.then(modifier)) { emptyDecorator(path.leafAddress) }
        return
    }

    val anyPinned = content.widgets.any { it.isPinned }
    when (val mode = content.viewportMode) {
        // The chrome goes on the viewport, which is the slot as anybody sees it, and
        // not on the content inside, which is taller than the slot and mostly off it.
        is ViewportMode.Scroll -> {
            val scrolled: @Composable (Modifier) -> Unit = { viewport ->
                ScrollViewport(
                    horizontal = mode.horizontal,
                    paged = mode.paged,
                    scrollbar = content.viewport?.scrollbar != false,
                    outer = viewport,
                    contentPadding = contentPadding,
                    overlay = if (anyPinned && content.flow == null) {
                        { PinnedPlacement(path, content, spacing) }
                    } else {
                        null
                    },
                ) { scroll, inner ->
                    SlotBody(path, content, inner, spacing, scroll, include = { !it.isPinned })
                }
            }
            if (anyPinned && content.flow != null) {
                PinnedFlowFrame(path, content, mode.horizontal, chrome.then(modifier), spacing, scrolled)
            } else {
                scrolled(chrome.then(modifier))
            }
        }
        // A map holds placed widgets. A flow on one is shown as it would be static
        // until the slot is placed, which the editor does in the same step anyway.
        ViewportMode.Map -> if (content.flow == null) {
            MapViewport(
                content = content,
                outer = chrome.then(modifier),
                overlay = if (anyPinned) ({ PinnedPlacement(path, content, spacing) }) else null,
            ) { pan, inView -> MapPlacement(path, content, pan, inView) }
        } else {
            SlotBody(path, content, chrome.then(modifier).inset(contentPadding), spacing, scroll = null)
        }
        ViewportMode.Static -> SlotBody(path, content, chrome.then(modifier).inset(contentPadding), spacing, scroll = null)
    }
}

/** Whether the widget is held in place while its slot moves. See [Placement.pinned]. */
private val WidgetInstance.isPinned: Boolean get() = placement?.pinned == true

/**
 * A scrolling flow with pinned widgets: they stand at the start of the axis, in
 * their order, outside the part that moves, and the moving part takes the rest.
 * A header that stays at the top of a page, or a column that stays at the left of
 * a page that scrolls sideways.
 */
@Composable
private fun PinnedFlowFrame(
    path: SlotPath,
    content: SlotContent,
    horizontal: Boolean,
    outer: Modifier,
    spacing: Dp,
    scrolled: @Composable (Modifier) -> Unit,
) {
    val registry = LocalWidgetRegistry.current
    val decorator = LocalWidgetDecorator.current
    val unknownDecorator = LocalUnknownWidgetDecorator.current
    val pinned = content.widgets.withIndex().filter { it.value.isPinned }
    // A weight means a share of the moving part, which a pinned widget is not in,
    // so in the strip every widget is its own size.
    val natural: Weigh = { _, _ -> Modifier }
    if (horizontal) {
        Row(outer, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            Row(Modifier.withoutLeadingGap(spacing, horizontal = true)) {
                FlowWidgets(path.leafAddress, pinned, registry, decorator, unknownDecorator, natural, Modifier.gapBefore(spacing, horizontal = true))
            }
            scrolled(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(outer, verticalArrangement = Arrangement.spacedBy(spacing)) {
            Column(Modifier.withoutLeadingGap(spacing, horizontal = false)) {
                FlowWidgets(path.leafAddress, pinned, registry, decorator, unknownDecorator, natural, Modifier.gapBefore(spacing, horizontal = false))
            }
            scrolled(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * The pinned widgets of a placement slot that moves, laid over the view rather
 * than the content: each by its own anchor against the view's box, so a corner
 * means a corner of the window again, as it does in a slot that does not move.
 */
@Composable
private fun BoxScope.PinnedPlacement(path: SlotPath, content: SlotContent, spacing: Dp) {
    PlacementSlot(
        path = path,
        content = content,
        address = path.leafAddress,
        registry = LocalWidgetRegistry.current,
        decorator = LocalWidgetDecorator.current,
        unknownDecorator = LocalUnknownWidgetDecorator.current,
        outer = Modifier.matchParentSize(),
        spacing = spacing,
        scroll = null,
        include = { it.isPinned },
        reportBounds = false,
    )
}

@Composable
private fun SlotBody(
    path: SlotPath,
    content: SlotContent,
    outer: Modifier,
    spacing: Dp,
    scroll: ScrollAxis?,
    include: (WidgetInstance) -> Boolean = { true },
) {
    val registry = LocalWidgetRegistry.current
    val decorator = LocalWidgetDecorator.current
    val unknownDecorator = LocalUnknownWidgetDecorator.current
    val address = path.leafAddress
    val flow = content.flow
    if (flow == null) {
        PlacementSlot(path, content, address, registry, decorator, unknownDecorator, outer, spacing, scroll, include)
    } else {
        FlowSlot(flow, content, address, registry, decorator, unknownDecorator, outer, spacing, LocalSlotMotionMs.current, scroll, include)
    }
}

// ── Viewport ─────────────────────────────────────────────────────────

/**
 * The axis a slot scrolls on, and how long one screen of it is.
 *
 * Handed to the two arrangements as an argument and never through a composition
 * local, because it describes THIS slot's content: a placement slot inside a
 * widget inside a scrolling page is not itself scrolling, and a local would have
 * told it that it was.
 */
private data class ScrollAxis(val horizontal: Boolean, val extent: Dp)

/**
 * A slot that is unbounded along one axis and moves along it.
 *
 * The box is the slot's own footprint, sized by the surface like any other slot.
 * Inside it the content is measured with no limit on the scrolling axis and moved
 * under the box, which is what makes a page longer than the window.
 *
 * A slot given no bound on that axis by its parent (a scrolling slot nested in
 * another that scrolls the same way, inside a widget that declared no ceiling)
 * has no screen to scroll against, so it lays out as static and the outer one
 * does the scrolling.
 *
 * The position is saved, so a page left and come back to opens where it was left.
 * The router keeps saved state per destination, which is what this rides on.
 */
@Composable
private fun ScrollViewport(
    horizontal: Boolean,
    paged: Boolean,
    scrollbar: Boolean,
    outer: Modifier,
    contentPadding: PaddingValues,
    overlay: (@Composable BoxScope.() -> Unit)?,
    body: @Composable (ScrollAxis?, Modifier) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val direction = LocalLayoutDirection.current
    val state = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }
    val handle = remember(state, horizontal) { ScrollViewportHandle(horizontal, state) }
    val turns = rememberCoroutineScope()
    // One screen, in px, for turning pages: a plain field the wheel reads when it
    // turns, set where the box is measured. Zero until then.
    val page = remember { PageLength() }
    val paging = if (paged) {
        Modifier.pointerInput(horizontal) { pageTurns(state, horizontal, { page.px }, turns) }
    } else {
        Modifier
    }
    BoxWithConstraints(outer.hoverable(interaction).onGloballyPositioned { handle.bounds = it.boundsInWindow() }.then(paging)) {
        val along = if (horizontal) maxWidth else maxHeight
        if (!along.isFinite) {
            body(null, Modifier.inset(contentPadding))
            return@BoxWithConstraints
        }
        val padding = if (horizontal) {
            contentPadding.calculateStartPadding(direction) + contentPadding.calculateEndPadding(direction)
        } else {
            contentPadding.calculateTopPadding() + contentPadding.calculateBottomPadding()
        }
        val extent = (along - padding).coerceAtLeast(0.dp)
        val pagePx = with(LocalDensity.current) { extent.roundToPx() }
        page.px = pagePx
        // A page turned by anything other than the wheel, the bar dragged or a
        // touchpad, settles on the nearer page once it stops.
        if (paged) {
            val moving = state.isScrollInProgress
            LaunchedEffect(moving, pagePx) {
                if (moving || pagePx <= 0) return@LaunchedEffect
                val nearest = (state.value.toFloat() / pagePx).roundToInt() * pagePx
                if (nearest != state.value) state.animateScrollTo(nearest.coerceIn(0, state.maxValue))
            }
        }
        val parent = LocalViewportExtent.current
        val here = if (horizontal) parent.copy(width = extent) else parent.copy(height = extent)
        val scroller = if (horizontal) {
            Modifier.horizontalScroll(state).fillMaxHeight()
        } else {
            Modifier.verticalScroll(state).fillMaxWidth()
        }
        CompositionLocalProvider(LocalViewportExtent provides here, LocalViewport provides handle) {
            body(ScrollAxis(horizontal, extent), scroller.inset(contentPadding))
        }
        overlay?.invoke(this)
        if (scrollbar) {
            val bar = LocalViewportScrollbar.current
            bar(state, horizontal, hovered || state.isScrollInProgress)
        }
    }
}

/** How long one page of a paged slot is, in px. */
private class PageLength {
    var px: Int = 0
}

/**
 * The wheel on a paged slot: one turn of it, one page, whichever way it went.
 *
 * Taken on the first pass, before the scroll under it sees the event, and only
 * while no turn is still running, so a burst of notches from one flick is one page
 * rather than six. A slot that pages sideways turns on the ordinary wheel too,
 * which is the wheel a desktop mouse has.
 */
private suspend fun PointerInputScope.pageTurns(
    state: ScrollState,
    horizontal: Boolean,
    pagePx: () -> Int,
    turns: CoroutineScope,
) = awaitPointerEventScope {
    var turning: Job? = null
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.type != PointerEventType.Scroll) continue
        val change = event.changes.firstOrNull() ?: continue
        if (change.isConsumed) continue
        val delta = change.scrollDelta
        val along = if (horizontal && delta.x != 0f) delta.x else delta.y
        if (along == 0f) continue
        change.consume()
        val page = pagePx()
        if (page <= 0 || turning?.isActive == true) continue
        val current = (state.value.toFloat() / page).roundToInt()
        val target = ((current + if (along > 0f) 1 else -1) * page).coerceIn(0, state.maxValue)
        turning = turns.launch { state.animateScrollTo(target) }
    }
}

/**
 * A slot with no edges: a plane the view is moved over on both axes.
 *
 * The box is the slot's footprint like any other slot's. The plane behind it is
 * moved by [pan], the position of the plane's origin in the box, in px. The wheel
 * moves it down, the wheel with Shift moves it sideways, a touchpad moves it both
 * ways, and a drag that starts on nothing the widgets claimed moves it under the
 * pointer, after the touch slop so a click on a widget stays a click.
 *
 * Inside a page that scrolls, the wheel is the page's: a plane has no end to hand
 * the wheel back at, so a map that took it would stop the page dead. There the map
 * is moved by dragging only.
 *
 * The position is saved like a scroll position is.
 */
@Composable
private fun MapViewport(
    content: SlotContent,
    outer: Modifier,
    overlay: (@Composable BoxScope.() -> Unit)?,
    body: @Composable (State<Offset>, MutableState<Boolean>) -> Unit,
) {
    val pan = rememberSaveable(saver = PanSaver) { mutableStateOf(Offset.Zero) }
    // Whether any widget is in view, answered by the layout, which is the one place
    // that knows where each of them is drawn.
    val inView = remember { mutableStateOf(true) }
    val handle = remember(pan) { MapViewportHandle(pan) }
    val insidePage = LocalViewport.current != null
    val panOnPrimary = LocalMapPanOnPrimary.current
    val density = LocalDensity.current
    val wheelStep = with(density) { MAP_WHEEL_STEP.toPx() }
    val margin = with(density) { MAP_HOME_MARGIN.toPx() }
    // Where the plane sits when its content's top left corner is near the view's.
    val home = remember(content.widgets, density) {
        val placed = content.widgets.mapNotNull { it.placement }
        if (placed.isEmpty()) {
            Offset.Zero
        } else {
            Offset(margin - placed.minOf { it.x } * density.density, margin - placed.minOf { it.y } * density.density)
        }
    }
    BoxWithConstraints(
        outer
            .clipToBounds()
            .onGloballyPositioned { handle.bounds = it.boundsInWindow() }
            .pointerInput(insidePage, panOnPrimary, wheelStep) { mapGestures(pan, insidePage, panOnPrimary, wheelStep) },
    ) {
        CompositionLocalProvider(
            LocalViewportExtent provides ViewportExtent(maxWidth, maxHeight),
            LocalViewport provides handle,
        ) {
            body(pan, inView)
        }
        overlay?.invoke(this)
        // Lost: nothing on the map is in view. Written by the layout only when the
        // answer flips, so moving the map does not recompose this on every pixel.
        val away = !inView.value && content.widgets.isNotEmpty()
        val controls = LocalMapControls.current
        controls(away) { pan.value = home }
    }
}

/** The wheel and the drag that move a map, after its widgets have had the event. */
private suspend fun PointerInputScope.mapGestures(
    pan: MutableState<Offset>,
    insidePage: Boolean,
    panOnPrimary: Boolean,
    wheelStep: Float,
) = awaitPointerEventScope {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull() ?: continue
        when (event.type) {
            PointerEventType.Scroll -> {
                if (insidePage || change.isConsumed) continue
                var delta = change.scrollDelta
                if (event.keyboardModifiers.isShiftPressed && delta.x == 0f) delta = Offset(delta.y, 0f)
                pan.value -= delta * wheelStep
                change.consume()
            }
            PointerEventType.Press -> {
                val wanted = (panOnPrimary && event.buttons.isPrimaryPressed) || event.buttons.isTertiaryPressed
                if (!wanted || change.isConsumed) continue
                val started = awaitTouchSlopOrCancellation(change.id) { moved, over ->
                    pan.value += over
                    moved.consume()
                } ?: continue
                drag(started.id) { moved ->
                    pan.value += moved.positionChange()
                    moved.consume()
                }
            }
            else -> Unit
        }
    }
}

/** A map's view: moving it on moves the plane's origin back by as much. */
private class MapViewportHandle(private val pan: MutableState<Offset>) : ViewportHandle() {
    override val movesX: Boolean get() = true
    override val movesY: Boolean get() = true

    override fun scrollBy(delta: Offset): Offset {
        pan.value -= delta
        return delta
    }
}

private val PanSaver: Saver<MutableState<Offset>, List<Float>> = Saver(
    save = { listOf(it.value.x, it.value.y) },
    restore = { mutableStateOf(Offset(it[0], it[1])) },
)

/** Where a map's box and its plane's origin last were on screen, in window px. */
private class MapWhere {
    var visible: Rect = Rect.Zero
    var origin: Offset = Offset.Zero
}

/** How far one notch of the wheel moves a map. */
private val MAP_WHEEL_STEP = 64.dp

/** How far in from the view's corner the content sits when the map is sent home. */
private val MAP_HOME_MARGIN = 24.dp

/**
 * The widgets of a map, each at its own point on the plane, all moved by [pan].
 *
 * Measured with no bound on either axis, so each widget is its claimed size or its
 * own, under the one-screen ceiling the map hands down. Moving the map is a
 * placement pass and nothing more: the widgets are not measured again, let alone
 * composed again, for every pixel the plane moves.
 */
@Composable
private fun MapPlacement(path: SlotPath, content: SlotContent, pan: State<Offset>, inView: MutableState<Boolean>) {
    val registry = LocalWidgetRegistry.current
    val decorator = LocalWidgetDecorator.current
    val unknownDecorator = LocalUnknownWidgetDecorator.current
    val reportSlotBounds = LocalSlotBoundsReporter.current
    val withdrawSlotBounds = LocalSlotBoundsWithdrawal.current
    DisposableEffect(path, withdrawSlotBounds) { onDispose { withdrawSlotBounds(path) } }
    val extent = LocalViewportExtent.current
    val address = path.leafAddress
    val slotDp = Size(extent.width.value, extent.height.value)
    val bounds = PlacementBounds(unboundedX = true, unboundedY = true)
    // The plane's origin on screen, reported with what is visible so a drop converts
    // against the plane, wherever it has been moved to. Plain fields reported from
    // the two layout callbacks, so moving the map is not a recomposition here.
    val where = remember { MapWhere() }
    val report = rememberUpdatedState(reportSlotBounds)
    fun publish() = report.value(path, where.visible, Rect(where.origin, where.visible.size))
    CompositionLocalProvider(
        LocalPlacementSlotSizeDp provides slotDp,
        LocalGridGeometry provides null,
        LocalPlacementBounds provides bounds,
    ) {
        Layout(
            content = {
                Box(Modifier.onGloballyPositioned { where.origin = it.positionInWindow(); publish() })
                content.widgets.withIndex()
                    .filter { !it.value.isPinned }
                    .sortedWith(compareBy({ it.value.placement?.z ?: 0 }, { it.index }))
                    .forEach { (index, instance) ->
                        key(instance.instanceId) {
                            val p = instance.placement ?: Placement()
                            val descriptor = registry[instance.kind]
                            val sizing = descriptor?.sizing ?: WidgetSizing.UNDECLARED
                            PlacedBox(p, 0, 0f, 0f, slotDp, sizing, bounds, { Modifier.layoutId(it) }) {
                                if (descriptor == null) {
                                    RenderUnknown(unknownDecorator, address, index, instance)
                                } else {
                                    val movable = rememberWidgetMovable(descriptor, instance, index)
                                    decorator(address, index, descriptor, instance) { movable() }
                                }
                            }
                        }
                    }
            },
            modifier = Modifier.fillMaxSize().onGloballyPositioned { where.visible = it.boundsInWindow(); publish() },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(Constraints()) }
            val at = measurables.map { it.layoutId as? PlacedAt }
            layout(constraints.maxWidth, constraints.maxHeight) {
                val o = pan.value
                placeables.forEach { it.place(o.x.roundToInt(), o.y.roundToInt()) }
                // A widget is drawn at the plane's origin plus its own offset, which
                // its box applies inside itself, so the offset comes from its record.
                val visible = placeables.indices.any { i ->
                    val where = at[i] ?: return@any false
                    val left = o.x + where.heldX.dp.toPx()
                    val top = o.y + where.heldY.dp.toPx()
                    left < constraints.maxWidth && left + placeables[i].width > 0 &&
                        top < constraints.maxHeight && top + placeables[i].height > 0
                }
                if (inView.value != visible) inView.value = visible
            }
        }
    }
}

/**
 * How a flow line hands out a weight, given the size rules of the widget taking it.
 *
 * Across a bounded axis it is Compose's own weight: a share of what the line has
 * left. Along a scrolling axis nothing is left, the axis has no end, and Compose
 * answers a weight there with nothing at all. So there it is a share of one screen:
 * a hero weighted 1 alone in its line is exactly the first screen, two weighted
 * 1 and 1 are half a screen each, and everything natural around them adds to the
 * page instead of squeezing them. Never below what the widget declared it needs.
 */
private typealias Weigh = (weight: Float, sizing: WidgetSizing) -> Modifier

private fun viewportShare(scroll: ScrollAxis, totalWeight: Float): Weigh = { weight, sizing ->
    val share = if (totalWeight > 0f) scroll.extent * (weight / totalWeight) else scroll.extent
    val floor = (if (scroll.horizontal) sizing.minWidth else sizing.minHeight).dp
    val size = maxOf(share, floor)
    if (scroll.horizontal) Modifier.width(size) else Modifier.height(size)
}

/** Total weight of the weighted children in [line], for sharing one screen between them. */
private fun totalWeight(line: List<WidgetInstance>): Float =
    line.sumOf { (it.flowPlacement() as? FlowPlacement.Weighted)?.weight?.toDouble() ?: 0.0 }.toFloat()

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
    outerModifier: Modifier,
    spacing: Dp,
    motionMs: Int,
    scroll: ScrollAxis?,
    include: (WidgetInstance) -> Boolean,
) {
    val outer = outerModifier.animatedReflow(motionMs)
    val lineLength = flow.wrap.coerceAtLeast(0)
    // Each with its place in the whole slot, which is what the editor addresses a
    // widget by, whichever of them this pass draws.
    val items = content.widgets.withIndex().filter { include(it.value) }
    val drawn = items.map { it.value }

    // A line along the scrolling axis shares one screen between its weights; a line
    // across it is bounded and keeps Compose's own weight.
    fun alongScroll(lineHorizontal: Boolean): ScrollAxis? = scroll?.takeIf { it.horizontal == lineHorizontal }

    if (lineLength == 0) {
        if (flow.horizontal) {
            Row(outer.withoutLeadingGap(spacing, horizontal = true)) {
                val weigh: Weigh = alongScroll(true)?.let { viewportShare(it, totalWeight(drawn)) }
                    ?: { w, _ -> Modifier.weight(w) }
                FlowWidgets(address, items, registry, decorator, unknownDecorator, weigh, Modifier.gapBefore(spacing, horizontal = true))
            }
        } else {
            Column(outer.withoutLeadingGap(spacing, horizontal = false)) {
                val weigh: Weigh = alongScroll(false)?.let { viewportShare(it, totalWeight(drawn)) }
                    ?: { w, _ -> Modifier.weight(w) }
                FlowWidgets(address, items, registry, decorator, unknownDecorator, weigh, Modifier.gapBefore(spacing, horizontal = false))
            }
        }
        return
    }

    // Wrapped: lines of `wrap` children, laid across the flow direction and
    // stacked along the other one. A uniform line gives every cell an equal
    // share and pads the short last line with weighted spacers so the columns
    // stay aligned; a non-uniform one lets each child take its own size.
    val lines = items.chunked(lineLength)
    if (flow.horizontal) {
        Column(outer, verticalArrangement = Arrangement.spacedBy(spacing)) {
            lines.forEachIndexed { lineIndex, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    val weigh: Weigh = alongScroll(true)?.let { viewportShare(it, lineLength.toFloat()) }
                        ?: { w, _ -> Modifier.weight(w) }
                    WrappedLine(address, line, flow.uniform, registry, decorator, unknownDecorator, weigh)
                    if (flow.uniform) repeat(lineLength - line.size) { Spacer(weigh(1f, WidgetSizing.UNDECLARED)) }
                }
            }
        }
    } else {
        Row(outer, horizontalArrangement = Arrangement.spacedBy(spacing)) {
            lines.forEachIndexed { lineIndex, line ->
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    val weigh: Weigh = alongScroll(false)?.let { viewportShare(it, lineLength.toFloat()) }
                        ?: { w, _ -> Modifier.weight(w) }
                    WrappedLine(address, line, flow.uniform, registry, decorator, unknownDecorator, weigh)
                    if (flow.uniform) repeat(lineLength - line.size) { Spacer(weigh(1f, WidgetSizing.UNDECLARED)) }
                }
            }
        }
    }
}

/**
 * The gap of a flow line, given only before a widget that drew something.
 *
 * A widget can have nothing to show where it stands: a filter for a kind that is
 * not being searched, a project block for a project that declares nothing. It keeps
 * its place in the flow at no size, and a plain spacedBy still gave it the gap on
 * either side, so the line grew a double gap wherever one stood. Here a widget of
 * no size along the line takes no gap, and one that draws takes it before itself.
 *
 * Carried by the widgets rather than by the line's arrangement. An arrangement only
 * places what the line has already measured, and the line had measured a gap for
 * every widget, drawn or not, so a column of empty blocks still stood taller than
 * what it drew. The line hands back the gap before its first widget, see
 * [withoutLeadingGap].
 */
private fun Modifier.gapBefore(gap: Dp, horizontal: Boolean): Modifier = if (gap <= 0.dp) this else layout { measurable, constraints ->
    val g = gap.roundToPx()
    val placeable = measurable.measure(if (horizontal) constraints.offset(horizontal = -g) else constraints.offset(vertical = -g))
    val along = if (horizontal) placeable.width else placeable.height
    when {
        along == 0 -> layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        horizontal -> layout(placeable.width + g, placeable.height) { placeable.placeRelative(g, 0) }
        else -> layout(placeable.width, placeable.height + g) { placeable.placeRelative(0, g) }
    }
}

/**
 * A line of [gapBefore] widgets, less the gap before the first of them: the line is
 * measured with that much more room, then reports that much less and is drawn back
 * by it, so the first drawn widget meets the line's edge.
 */
private fun Modifier.withoutLeadingGap(gap: Dp, horizontal: Boolean): Modifier = if (gap <= 0.dp) this else layout { measurable, constraints ->
    val g = gap.roundToPx()
    val placeable = measurable.measure(if (horizontal) constraints.offset(horizontal = g) else constraints.offset(vertical = g))
    if (horizontal) {
        val width = constraints.constrainWidth((placeable.width - g).coerceAtLeast(0))
        layout(width, placeable.height) { placeable.placeRelative(-g, 0) }
    } else {
        val height = constraints.constrainHeight((placeable.height - g).coerceAtLeast(0))
        layout(placeable.width, height) { placeable.placeRelative(0, -g) }
    }
}

@Composable
private fun FlowWidgets(
    address: SlotAddress,
    widgets: List<IndexedValue<WidgetInstance>>,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    weigh: Weigh,
    /** What each widget wears for the line's gap, outside its own size. */
    gap: Modifier,
) {
    widgets.forEach { (index, instance) ->
        key(instance.instanceId) {
            val descriptor = registry[instance.kind]
            if (descriptor == null) {
                Box(gap) { RenderUnknown(unknownDecorator, address, index, instance) }
            } else {
                val movable = rememberWidgetMovable(descriptor, instance, index)
                // Outer spacing around the widget, from its placement so a flow
                // widget reserves room the same way a placed one does.
                val pad = Modifier.padding((instance.placement?.padding ?: SurfaceInsets()).asPadding())
                // Precedence lives on the model as flowPlacement(), so the rule is
                // testable without a composition.
                when (val placement = instance.flowPlacement()) {
                    is FlowPlacement.Weighted -> Box(gap.then(weigh(placement.weight, descriptor.sizing)).then(pad)) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
                    is FlowPlacement.Bounded -> Box(gap.then(boundedModifier(placement, descriptor.sizing)).then(pad)) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
                    FlowPlacement.Natural -> Box(gap.then(pad)) {
                        decorator(address, index, descriptor, instance) { movable() }
                    }
                }
            }
        }
    }
}

// One line of a wrapped flow. Each child carries its position in the whole slot,
// not in the line, because that is what the drop hit-test and the decorator
// address it by.
@Composable
private fun WrappedLine(
    address: SlotAddress,
    line: List<IndexedValue<WidgetInstance>>,
    uniform: Boolean,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    weigh: Weigh,
) {
    line.forEach { (index, instance) ->
        key(instance.instanceId) {
            val descriptor = registry[instance.kind]
            val cell: Modifier = if (uniform) weigh(1f, descriptor?.sizing ?: WidgetSizing.UNDECLARED) else Modifier
            Box(cell) {
                if (descriptor == null) {
                    RenderUnknown(unknownDecorator, address, index, instance)
                } else {
                    val movable = rememberWidgetMovable(descriptor, instance, index)
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
//
// In a slot that scrolls, the lattice counts its lines across the bounded axis:
// columns when the slot scrolls down, rows when it scrolls sideways, so the same
// number in the same menu always means "this many across the side that does not
// move", and the lattice grows along the side that does.
@Composable
private fun PlacementSlot(
    path: SlotPath,
    content: SlotContent,
    address: SlotAddress,
    registry: WidgetRegistry,
    decorator: WidgetDecorator,
    unknownDecorator: UnknownWidgetDecorator,
    outer: Modifier,
    spacing: Dp,
    scroll: ScrollAxis?,
    include: (WidgetInstance) -> Boolean = { true },
    // Off for the layer of pinned widgets over a moving slot: the slot itself
    // reports where its content is, and two reports would fight over one path.
    reportBounds: Boolean = true,
) {
    val density = LocalDensity.current
    val reportSlotBounds = if (reportBounds) LocalSlotBoundsReporter.current else NO_REPORT
    if (reportBounds) {
        val withdrawSlotBounds = LocalSlotBoundsWithdrawal.current
        DisposableEffect(path, withdrawSlotBounds) { onDispose { withdrawSlotBounds(path) } }
    }
    val columns = content.grid.coerceIn(0, GRID_MAX)
    val transposed = scroll?.horizontal == true
    val placementBounds = PlacementBounds(unboundedX = scroll?.horizontal == true, unboundedY = scroll?.horizontal == false)

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
        outer
            .onSizeChanged { sz ->
                measuredDp = with(density) { Size(sz.width.toDp().value, sz.height.toDp().value) }
            }
            .onGloballyPositioned {
                reportSlotBounds(path, it.boundsInWindow(), Rect(it.positionInWindow(), it.size.toSize()))
            },
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
        // slot whose lattice axis is unbounded, where a fraction of it has nothing
        // to be a fraction of.
        val across = if (transposed) boundedHeight else boundedWidth
        val cell: Float = if (columns > 0 && across > 0f) {
            ((across - spacing.value * (columns + 1)) / columns).coerceAtLeast(0f)
        } else {
            0f
        }

        val placed: @Composable (position: (PlacedAt) -> Modifier) -> Unit = { position ->
            content.widgets.withIndex()
                .filter { include(it.value) }
                .sortedWith(compareBy({ it.value.placement?.z ?: 0 }, { it.index }))
                .forEach { (index, instance) ->
                    key(instance.instanceId) {
                        val p = instance.placement ?: Placement()
                        val descriptor = registry[instance.kind]
                        val sizing = descriptor?.sizing ?: WidgetSizing.UNDECLARED
                        PlacedBox(p, columns, cell, spacing.value, clampSize, sizing, placementBounds, position) {
                            if (descriptor == null) {
                                RenderUnknown(unknownDecorator, address, index, instance)
                            } else {
                                val movable = rememberWidgetMovable(descriptor, instance, index)
                                decorator(address, index, descriptor, instance) { movable() }
                            }
                        }
                    }
                }
        }

        CompositionLocalProvider(
            LocalPlacementSlotSizeDp provides measuredDp,
            LocalPlacementBounds provides placementBounds,
            // Published only when there is a cell to convert against. A geometry
            // carrying a zero cell reads as a lattice to the editor and then
            // answers every pointer delta with "no movement", which is a gesture
            // that is present and does nothing.
            LocalGridGeometry provides if (columns > 0 && cell > 0f) GridGeometry(cell, spacing.value, columns, transposed) else null,
        ) {
            if (scroll == null) {
                Box(Modifier.fillMaxSize()) {
                    placed { Modifier.align(it.alignment) }
                }
            } else {
                ExtendingPlacement(scroll) {
                    placed { Modifier.layoutId(it) }
                }
            }
        }
    }
}

private val NO_REPORT: SlotBoundsReporter = { _, _, _ -> }

/**
 * Where a placed widget sits: its corner, the offset it is held at from that
 * corner in dp, and whether that corner is the start of each axis.
 */
private data class PlacedAt(
    val alignment: Alignment,
    val heldX: Float,
    val heldY: Float,
    val startX: Boolean,
    val startY: Boolean,
)

/**
 * The placement box of a slot that scrolls: as long as its furthest widget, and
 * never shorter than one screen.
 *
 * A plain box cannot answer that. It sizes itself from its children's sizes, and
 * a placed widget's offset is applied by the widget's own modifier after the box
 * has already decided, so a widget parked two screens down left a box one widget
 * tall and a page that would not scroll to it. This places children exactly the
 * way the box does, by their alignment against its final size, and measures the
 * length first.
 *
 * Only a widget attached to the start of the scrolling axis decides the length.
 * One attached to the middle or the far end is attached to the content's own
 * edge, so it follows the length rather than setting it, and letting it set it
 * would chase itself.
 */
@Composable
private fun ExtendingPlacement(scroll: ScrollAxis, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val at = measurables.map { it.layoutId as? PlacedAt }
        var length = scroll.extent.roundToPx()
        placeables.forEachIndexed { i, p ->
            val where = at[i] ?: return@forEachIndexed
            if (!(if (scroll.horizontal) where.startX else where.startY)) return@forEachIndexed
            val reach = if (scroll.horizontal) where.heldX.dp.roundToPx() + p.width else where.heldY.dp.roundToPx() + p.height
            length = max(length, reach)
        }
        val crossOf: (Int, Boolean) -> Int = { bound, bounded ->
            if (bounded) bound else placeables.maxOfOrNull { if (scroll.horizontal) it.height else it.width } ?: 0
        }
        val width = if (scroll.horizontal) length else crossOf(constraints.maxWidth, constraints.hasBoundedWidth)
        val height = if (scroll.horizontal) crossOf(constraints.maxHeight, constraints.hasBoundedHeight) else length
        layout(width, height) {
            placeables.forEachIndexed { i, p ->
                val alignment = at[i]?.alignment ?: Alignment.TopStart
                p.place(alignment.align(IntSize(p.width, p.height), IntSize(width, height), layoutDirection))
            }
        }
    }
}

// The room a widget has from its anchored edge to the far one, less its outer
// padding. A start or end anchor pins one edge and leaves the space to the other; a
// centre anchor grows both ways, so an offset eats twice.
private fun roomFromAnchor(slot: Float, offset: Float, bias: Float, pad: Float): Float {
    val room = if (bias == 0.5f) slot - 2f * abs(offset) else slot - offset
    return (room - pad).coerceAtLeast(0f)
}

// Positions one child against its anchor. Compose's own alignment does the bias
// arithmetic, so the offset is only the nudge away from that corner -- and it
// runs inward from an end anchor, because "16 from the right" is what somebody
// parking a widget in a corner means, not "16 further right than the edge".
//
// [bounds] says which axes the slot has no end on: one for a page, both for a map.
// A lattice on a page that scrolls sideways counts [columns] as rows and grows to
// the right. [position] attaches the corner the way the container needs it, as a
// box alignment or as data for a container that measures its own length.
@Composable
private fun PlacedBox(
    placement: Placement,
    columns: Int,
    cell: Float,
    gutter: Float,
    slotDp: Size,
    sizing: WidgetSizing,
    bounds: PlacementBounds,
    position: (PlacedAt) -> Modifier,
    content: @Composable () -> Unit,
) {
    val lattice = columns > 0
    val transposed = bounds.unboundedX && !bounds.unboundedY
    val stride = cell + gutter

    // A lattice clamps what it is given, the way the cube grid it replaces did.
    // Nothing keeps a stored position inside a lattice that has since been made
    // narrower: the count is a number in a menu, and reducing it used to leave a
    // widget parked at a column that no longer exists, drawn past the slot, out
    // of the window and out of reach, with no way back but to raise the count
    // again. The record is left alone and the drawing is clamped, so lowering the
    // count is reversible.
    //
    // Only across the bounded axis. Along a scrolling one the lattice has no last
    // line to clamp to.
    val spanW = when {
        !lattice -> placement.width
        transposed -> placement.width.coerceAtLeast(1f)
        else -> placement.width.coerceIn(1f, columns.toFloat())
    }
    val spanH = when {
        !lattice -> placement.height
        transposed -> placement.height.coerceIn(1f, columns.toFloat())
        else -> placement.height.coerceAtLeast(1f)
    }
    val col = when {
        !lattice -> placement.x
        transposed -> placement.x.coerceAtLeast(0f)
        else -> placement.x.coerceIn(0f, (columns - spanW).coerceAtLeast(0f))
    }
    val row = when {
        !lattice -> placement.y
        transposed -> placement.y.coerceIn(0f, (columns - spanH).coerceAtLeast(0f))
        else -> placement.y.coerceAtLeast(0f)
    }

    val offX = if (lattice) gutter + col * stride else col
    val offY = if (lattice) gutter + row * stride else row
    val width = if (lattice) spanW * stride - gutter else placement.width
    val height = if (lattice) spanH * stride - gutter else placement.height

    // On a map every widget counts from the plane's origin: there is no far edge to
    // count from. The record keeps its corner for whenever the slot stops being one.
    val anchor = if (bounds.anchorsIgnored) Placement.TOP_START else parseAnchor(placement.anchor)
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
    //
    // Along a scrolling axis a widget attached to the start is held off the start
    // and nowhere else: the page is as long as it reaches, so a far edge measured a
    // frame ago would hold it to last frame's length and the page would creep out to
    // it one frame at a time.
    val clampW = if (bounds.unboundedX && hBias == 0f) Float.POSITIVE_INFINITY else slotDp.width
    val clampH = if (bounds.unboundedY && vBias == 0f) Float.POSITIVE_INFINITY else slotDp.height
    //
    // A map has no edge at all, not even a start: a widget left of its origin is
    // where it was put, and the view is moved to it.
    val free = bounds.anchorsIgnored
    val clampedX = if (lattice || free || ownW <= 0f) offX else clampPlacementAxis(offX, clampW, ownW, hBias)
    val clampedY = if (lattice || free || ownH <= 0f) offY else clampPlacementAxis(offY, clampH, ownH, vBias)
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
    // Off while a placement is being edited, or the cap would fight the resize handle
    // (see [LocalPlacementReflow]). Authoring is at the natural size; the view reflows.
    val reflow = LocalPlacementReflow.current
    // Reflow to fit: cap the claim to the room the slot has left from the widget's
    // anchor, so a widget too wide for a shrunk slot (the right panel opening) draws
    // narrower and its content reflows, instead of running under the panel. Measured
    // from the anchored edge: an end anchor tracks the far edge and rarely overflows,
    // a start anchor's free side is the one that runs past. A widget parked beyond the
    // slot (offset past its width) is a stray the grab-margin clamp handles, so it is
    // left at its own size. Never below the widget's floor: if not even the floor fits
    // it keeps the floor and the overflow is irreducible. A lattice sizes in whole
    // cells and keeps its own clamp.
    //
    // Never along a scrolling axis. The slot's length there is measured from where
    // its widgets reach, so capping a widget to that length would hold it at the
    // size it had last frame and it could never grow.
    val availW = if (reflow && !lattice && !bounds.unboundedX && slotDp.width > 0f && offX <= slotDp.width) {
        roomFromAnchor(slotDp.width, clampedX, hBias, placement.padding.start(0f) + placement.padding.end(0f))
    } else {
        Float.POSITIVE_INFINITY
    }
    val availH = if (reflow && !lattice && !bounds.unboundedY && slotDp.height > 0f && offY <= slotDp.height) {
        roomFromAnchor(slotDp.height, clampedY, vBias, placement.padding.top(0f) + placement.padding.bottom(0f))
    } else {
        Float.POSITIVE_INFINITY
    }
    val fitW = if (boundW > 0f) minOf(boundW, availW).coerceAtLeast(sizing.minWidth.toFloat()) else if (availW.isFinite()) availW else 0f
    val fitH = if (boundH > 0f) minOf(boundH, availH).coerceAtLeast(sizing.minHeight.toFloat()) else if (availH.isFinite()) availH else 0f
    var sizeMod: Modifier = Modifier
    if (fitW > 0f) sizeMod = sizeMod.widthIn(max = fitW.dp)
    if (fitH > 0f) sizeMod = sizeMod.heightIn(max = fitH.dp)
    // Space reserved around the widget, from the placement rather than the plane,
    // so a widget that paints its own plane gets it too. Outside sizeMod and inside
    // onSizeChanged: the plane keeps its claimed size, the padding sits around it,
    // and what the clamp measures (ownDp) is the padded footprint, so the reserved
    // space stays inside the slot the same way the plane does. Never a Modifier.size,
    // so it offsets and reserves rather than shrinking the plane.
    val pad = placement.padding
    Box(
        position(PlacedAt(alignmentFor(anchor), heldX, heldY, startX = hBias == 0f, startY = vBias == 0f))
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
private fun rememberWidgetMovable(descriptor: WidgetDescriptor, instance: WidgetInstance, order: Int): @Composable () -> Unit {
    val descriptorState = rememberUpdatedState(descriptor)
    val instanceState = rememberUpdatedState(instance)
    val orderState = rememberUpdatedState(order)
    return remember { movableContentOf { RenderWidget(descriptorState.value, instanceState.value, orderState.value) } }
}

// Renders a widget, wrapped in the plane it resolves to. The wrap is inside the
// editor decorator (the drag handle and remove button surround the plane) but is
// PRODUCTION styling -- it paints whether the editor is mounted or not.
//
// Which plane it draws is [resolveSurface]'s answer, so the renderer and the
// editor's panel read the same one.
@Composable
private fun RenderWidget(descriptor: WidgetDescriptor, instance: WidgetInstance, order: Int) {
    val surface = descriptor.resolveSurface(instance)
    // The arrival wraps the plane too, so a widget and the panel under it come up
    // as one thing. Inside the movable content, so it plays once per mount and not
    // again when the editor relocates the widget.
    val entrance = LocalWidgetEntrance.current
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
        // added only for a widget that has a ceiling to apply, its own or a
        // scrolling slot's, so outside a scrolling slot nothing an undeclared widget
        // sees changes: most of the registry declares nothing and none of it should
        // start measuring differently for this.
        val ceiling = descriptor.sizing.unboundedAxisCeiling(LocalViewportExtent.current)
        entrance(descriptor.resolveEntrance(instance), order, instance.motion?.delayMs) {
            if (ceiling == null) body() else Box(ceiling) { body() }
        }
    }
}

/**
 * A widget whose kind is not in the registry, measured the way an undeclared one is.
 *
 * It has no declaration to read a ceiling from, and on an axis a scrolling slot or
 * a map leaves unbounded it was measured against nothing: the editor's stand-in
 * fills its width, a fill against no width is no width at all, and its label came
 * out one letter per line. The one-screen ceiling the known widgets get is the
 * answer for it too.
 */
@Composable
private fun RenderUnknown(decorator: UnknownWidgetDecorator, address: SlotAddress, index: Int, instance: WidgetInstance) {
    val ceiling = WidgetSizing.UNDECLARED.unboundedAxisCeiling(LocalViewportExtent.current)
    if (ceiling == null) decorator(address, index, instance) else Box(ceiling) { decorator(address, index, instance) }
}

/**
 * Substitutes a ceiling for an axis the widget was given no bound on, or null when
 * there is none to substitute. The widget's own declared maximum first, and inside
 * a scrolling slot, one screen of it ([viewport]).
 *
 * An unbounded axis is not a generous offer, it is the absence of an answer, and
 * a widget that scrolls or lazily lists cannot be measured against one: Compose
 * throws rather than guessing. That is reachable from the editor, because the
 * editor lets any widget be dropped in any slot and a slot inherits whatever its
 * surface hands down, and a scrolling slot hands down an unbounded axis by design.
 *
 * The declaration is the place that already answers "how much can this use", so
 * it answers first. Nothing is imposed where a bound exists: a slot that said a
 * height is obeyed, including one that said less than the widget wants, and the
 * claim rules in [boundedModifier] are untouched. This only fills a silence.
 *
 * Inside a scrolling slot an undeclared widget gets one screen. That is not a
 * guess at its size: it is the one length the page itself defines, and it is what
 * a list or a picture that fills reads as on any page, so a widget that fills its
 * slot fills the window and one that does not keeps its own size under it.
 *
 * Outside any scrolling slot an undeclared widget on an unbounded axis is left
 * exactly as it was, which is to say it still throws. Nothing there defines a
 * length, and inventing one would be this file guessing at a widget's size.
 */
private fun WidgetSizing.unboundedAxisCeiling(viewport: ViewportExtent): Modifier? {
    val ceilingW = if (maxWidth > 0) maxWidth.dp else viewport.width
    val ceilingH = if (maxHeight > 0) maxHeight.dp else viewport.height
    if (ceilingW == Dp.Unspecified && ceilingH == Dp.Unspecified) return null
    return Modifier.layout { measurable, constraints ->
        val filled = Constraints(
            minWidth = constraints.minWidth,
            // coerceAtLeast the minimum: a Constraints with max below min does not
            // exist, and an unbounded axis can still carry a minimum.
            maxWidth = if (ceilingW != Dp.Unspecified && constraints.maxWidth == Constraints.Infinity) {
                ceilingW.roundToPx().coerceAtLeast(constraints.minWidth)
            } else {
                constraints.maxWidth
            },
            minHeight = constraints.minHeight,
            maxHeight = if (ceilingH != Dp.Unspecified && constraints.maxHeight == Constraints.Infinity) {
                ceilingH.roundToPx().coerceAtLeast(constraints.minHeight)
            } else {
                constraints.maxHeight
            },
        )
        val placeable = measurable.measure(filled)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
