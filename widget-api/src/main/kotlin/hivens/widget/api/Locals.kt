package hivens.widget.api

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import hivens.widget.model.Entrance
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetSizing

/**
 * The arrangement every slot renders from.
 *
 * Dynamic, and the reason is the editor. A static local does not track reads: it
 * recomposes the WHOLE subtree it is provided over whenever its value changes,
 * which is cheap when the value changes on whole-tree events and ruinous when it
 * changes on every frame of a gesture. Dragging a widget writes a new graph per
 * pointer move, so this was recomposing the entire shell -- every widget on every
 * surface, the rails, the top bar and all sixty tiles of the open gallery -- sixty
 * times a second, to move one box.
 *
 * Ten places in the whole codebase read it. Tracking those ten is the cheaper
 * side of that trade by a very long way.
 */
val LocalLayoutGraph: ProvidableCompositionLocal<LayoutGraph> =
    compositionLocalOf { LayoutGraph.EMPTY }

// Static: the registry is built once at startup and a module registering into it
// is a whole-tree event, which is the case static is actually for.

val LocalWidgetRegistry: ProvidableCompositionLocal<WidgetRegistry> =
    staticCompositionLocalOf {
        error("LocalWidgetRegistry not provided -- did you wire WidgetRegistry in Koin?")
    }

// Decorator wraps every widget rendered by SlotRenderer. Default is
// the identity wrapper -- no overhead when nothing is provided. The
// editor swaps this for a chrome wrapper that adds drag handles and
// remove buttons. Keeps widget-api editor-agnostic; the implementation
// lives in :client-ui.
typealias WidgetDecorator = @Composable (
    address: SlotAddress,
    index: Int,
    descriptor: WidgetDescriptor,
    instance: WidgetInstance,
    content: @Composable () -> Unit,
) -> Unit

val LocalWidgetDecorator: ProvidableCompositionLocal<WidgetDecorator> =
    staticCompositionLocalOf {
        // identity wrapper -- zero decoration cost when no editor is
        // mounted (release builds, headless smoke, future TUI surface)
        { _, _, _, _, content -> content() }
    }

// Paints the optional per-instance surface around a widget --
// PRODUCTION styling, applied whenever instance.surface != null, not just in
// edit mode. Default = identity so the kernel stays Compose-token-agnostic;
// :client-ui provides the real renderer, so the plane follows the active palette
// rather than anything the kernel knows about.
typealias WidgetSurfaceRenderer = @Composable (surface: SurfaceSpec, content: @Composable () -> Unit) -> Unit

val LocalWidgetSurfaceRenderer: ProvidableCompositionLocal<WidgetSurfaceRenderer> =
    staticCompositionLocalOf { { _, content -> content() } }

/**
 * Plays a widget's arrival. [order] is its place in its slot and [delayMs] its own
 * pinned delay, null for the slot's stagger.
 *
 * The kernel knows which character a widget asked for and where it stands, and
 * nothing about time: durations and curves are the interface's motion scale,
 * which this module cannot see. So the default plays nothing, and :client-ui
 * provides the one that moves. Called inside the widget's movable content, so
 * toggling the editor, which relocates that content, does not play it again.
 */
typealias WidgetEntrance = @Composable (
    entrance: Entrance,
    order: Int,
    delayMs: Int?,
    content: @Composable () -> Unit,
) -> Unit

val LocalWidgetEntrance: ProvidableCompositionLocal<WidgetEntrance> =
    staticCompositionLocalOf { { _, _, _, content -> content() } }

// Rendered by SlotRenderer when a slot has no widgets. Default = nothing
// (production behavior: empty slot stays invisible). The editor swaps
// in a placeholder that says "drop here" and registers the slot bounds
// with the DropTargetRegistry, making empty slots valid drop targets.
typealias EmptySlotDecorator = @Composable (address: SlotAddress) -> Unit

val LocalEmptySlotDecorator: ProvidableCompositionLocal<EmptySlotDecorator> =
    staticCompositionLocalOf { {} }

// Rendered by SlotRenderer in place of a widget whose kind is absent from the
// registry (renamed, removed, or a plugin not loaded). Default = nothing --
// production keeps the slot clean while the instance's props / children stay on
// disk (non-destructive). The editor swaps in an "unsupported widget" placeholder
// so the user can see the orphan and remove it; the schema-bump prune reaps the
// truly-dead ones. Kept editor-agnostic here; the implementation lives in :client-ui.
typealias UnknownWidgetDecorator = @Composable (address: SlotAddress, index: Int, instance: WidgetInstance) -> Unit

val LocalUnknownWidgetDecorator: ProvidableCompositionLocal<UnknownWidgetDecorator> =
    staticCompositionLocalOf { { _, _, _ -> } }

// Phase G / Tier 2: a zero-footprint Modifier the editor applies to each slot's
// flow root (and the empty Box). It lets the editor highlight the slot, select it,
// and open its orientation menu WITHOUT being a layout child -- the old in-flow
// control displaced the edited content. Production default is the identity Modifier
// (no cost). Returns a plain Modifier so no nx-ui / editor type crosses into
// widget-api; the implementation lives in :client-ui.
typealias SlotChromeModifier = (path: SlotPath, content: SlotContent) -> Modifier

val LocalSlotChromeModifier: ProvidableCompositionLocal<SlotChromeModifier> =
    staticCompositionLocalOf { { _, _ -> Modifier } }

// Current path the surrounding SlotRenderer is rendering. Container
// widgets read this implicitly through the nested SlotRenderer
// overload; chrome / empty-placeholder use it to register drop-target
// bounds against the canonical path rather than just the leaf
// (SurfaceId, SlotId) pair, so nested containers do not collide on the
// registry. Must be inside a SlotRenderer to read.
val LocalSlotPath: ProvidableCompositionLocal<SlotPath> =
    staticCompositionLocalOf {
        error("LocalSlotPath not provided -- read inside a SlotRenderer body")
    }

// Cross-widget service registry. Provided once at the launcher's
// composition root from the Koin-bound singleton. Consumer widgets
// read via useService<T>() / useServiceByInstance / useAllServices;
// provider widgets register via provideService(...). Must be wired
// before any widget that participates in services renders.
val LocalWidgetServiceRegistry: ProvidableCompositionLocal<WidgetServiceRegistry> =
    staticCompositionLocalOf {
        error("LocalWidgetServiceRegistry not provided -- wire WidgetServiceRegistry in Koin and at the composition root")
    }

// App-provided reactive data sources widgets bind to via rememberSource(key).
// Provided once at the composition root from the Koin-bound singleton, like the
// service registry above. Static: the source set is fixed at startup; the
// reactivity is inside each source's StateFlow, not the registry membership.
val LocalWidgetDataRegistry: ProvidableCompositionLocal<WidgetDataRegistry> =
    staticCompositionLocalOf {
        error("LocalWidgetDataRegistry not provided -- wire WidgetDataRegistry in Koin and at the composition root")
    }

// App-provided commands widgets fire via rememberCommand(key) / rememberAction(key)
// -- the write counterpart of LocalWidgetDataRegistry. Provided once at the
// composition root from the Koin-bound singleton. Static for the same reason: the
// command set is fixed at startup.
val LocalWidgetCommandRegistry: ProvidableCompositionLocal<WidgetCommandRegistry> =
    staticCompositionLocalOf {
        error("LocalWidgetCommandRegistry not provided -- wire WidgetCommandRegistry in Koin and at the composition root")
    }

// Backs per-instance widget state (rememberWidgetState). Provided once at the
// composition root from the Koin-bound store. Static: the host reference is fixed
// at startup; the per-instance state lives in the store, not in this Local.
val LocalWidgetStateHost: ProvidableCompositionLocal<WidgetStateHost> =
    staticCompositionLocalOf {
        error("LocalWidgetStateHost not provided -- wire WidgetStateStore in Koin and at the composition root")
    }

// Edit-mode slot reflow duration (ms). 0 = no animation (the production
// default, since the only provider is the editor host). While editing, the host
// supplies the panelSlide role's duration, so add / remove / resize reflow
// animates in the editor only. Static is fine -- it changes only on the
// edit-mode toggle.
val LocalSlotMotionMs: ProvidableCompositionLocal<Int> =
    staticCompositionLocalOf { 0 }

// Measured size (dp) of the current placement slot's content box, published by
// SlotRenderer's placement branch. The editor's move gesture reads it to clamp a
// placed widget so a grab margin always stays inside (a widget can't be dragged
// fully out of reach). Zero -- the default, and inside a flow slot -- disables
// clamping. Dynamic (not static): it updates from onSizeChanged on every slot
// resize, and a static local would recompose the whole subtree on each change
// rather than just the chrome that reads it.
val LocalPlacementSlotSizeDp: ProvidableCompositionLocal<Size> =
    compositionLocalOf { Size.Zero }

/**
 * The room this widget was given on purpose, in dp, with zero on an axis nobody
 * named.
 *
 * Published by the placement branch from the widget's own stored size, so it says
 * "somebody chose this" and not "this is what happened to be free". A flow slot
 * names nothing and leaves both axes zero.
 *
 * The difference matters to a widget that adapts to its footprint. Bounded is not
 * the same as chosen: a Column inside a slot that fills its surface hands each
 * child the whole remaining height, so a widget reading "both axes are bounded"
 * as "I have been given a footprint" scaled itself to the rest of the screen. The
 * clock did exactly that and drew a 140dp dial across 2974 points of it.
 */
val LocalWidgetFootprintDp: ProvidableCompositionLocal<Size> =
    compositionLocalOf { Size.Zero }

/**
 * What the widget currently rendering declared about its own size.
 *
 * Published by [SlotRenderer] around each widget from its descriptor, so a
 * widget's body can read the numbers its own declaration carries without being
 * handed its descriptor. Undeclared -- the default, and what a widget rendered
 * outside a slot sees -- means the reader falls back to whatever it did before
 * anything declared anything.
 *
 * Dynamic rather than static because it differs per widget: a static local
 * provided around every one of them would invalidate the whole subtree each
 * time the provider moved on to the next.
 */
val LocalWidgetSizing: ProvidableCompositionLocal<WidgetSizing> =
    compositionLocalOf { WidgetSizing.UNDECLARED }

// Lattice cell geometry published by SlotRenderer's placement branch (dp): the
// editor's move / resize gestures read it to turn a pointer delta into a whole
// number of cells. Null in a free placement slot, where the unit is already the
// dp and nothing has to be converted. Dynamic, like the size above -- it updates
// as the slot is measured; only the chrome that reads it recomposes.
//
// [transposed] is a lattice in a slot that scrolls sideways: the bounded axis is
// the height there, so [columns] counts rows, the cell comes from the height, and
// the lattice grows to the right instead of down.
data class GridGeometry(val cellDp: Float, val gutterDp: Float, val columns: Int, val transposed: Boolean = false)

val LocalGridGeometry: ProvidableCompositionLocal<GridGeometry?> =
    compositionLocalOf { null }

// Whether a placement slot may reflow a widget to fit: cap its drawn width and
// height to the room its anchor leaves, so an overflowing widget draws smaller
// instead of running past the slot edge (and under the panel that shrank it). On
// for display, OFF while a placement is being edited: a widget capped to the slot
// cannot be resized past it, because the resize handle hangs off the drawn box and
// the cap freezes it. The editor turns it off so authoring happens at the natural
// size, and the reflow is a view-time transform on top.
val LocalPlacementReflow: ProvidableCompositionLocal<Boolean> =
    compositionLocalOf { true }

/**
 * The room of the nearest scrolling slot above, per axis, Unspecified where
 * nothing above scrolls on that axis.
 *
 * A scrolling slot hands its content an unbounded axis, which is what lets the
 * content be longer than the window. A widget that fills, lists lazily or scrolls
 * itself cannot be measured against an unbounded axis: Compose throws for some of
 * them and draws the others at nothing. So the kernel gives each widget its own
 * declared maximum there, and failing that, this: one viewport.
 *
 * It reaches through nested slots on purpose. A widget inside a container inside
 * a scrolling page is still on that page, and a bounded axis further down simply
 * never asks.
 */
@Immutable
data class ViewportExtent(val width: Dp = Dp.Unspecified, val height: Dp = Dp.Unspecified) {
    companion object {
        val NONE = ViewportExtent()
    }
}

val LocalViewportExtent: ProvidableCompositionLocal<ViewportExtent> =
    compositionLocalOf { ViewportExtent.NONE }

/**
 * Draws the bar of a scrolling slot, inside the slot's own box. [revealed] is the
 * kernel's "somebody is looking": the pointer is over the slot, or it is moving.
 *
 * The kernel knows that a slot scrolls and nothing about how a bar looks, which
 * belongs to the theme it cannot see. So the default draws nothing, and the app
 * provides the one that matches its other lists.
 */
typealias ViewportScrollbar = @Composable BoxScope.(state: ScrollState, horizontal: Boolean, revealed: Boolean) -> Unit

val LocalViewportScrollbar: ProvidableCompositionLocal<ViewportScrollbar> =
    staticCompositionLocalOf { { _, _, _ -> } }

/**
 * The surfaces open above the slot rendering now, innermost last.
 *
 * A widget does not contain a surface, it opens one, so nothing in the model can
 * say that a surface ends up inside itself: the edge only exists inside a widget's
 * body. Composition is depth first, so the check is a stack. A surface that finds
 * itself already on it refuses to open and draws [LocalRefusedMount] instead,
 * before the recursion can reach the stack's own limit. Two of the same surface
 * side by side are different branches and both open.
 */
val LocalMountedSurfaces: ProvidableCompositionLocal<Set<SurfaceId>> = compositionLocalOf { emptySet() }

/**
 * Drawn where a surface refused to open inside itself. Nothing by default, which
 * is what a person not arranging anything should see; the editor says why.
 */
val LocalRefusedMount: ProvidableCompositionLocal<@Composable (SurfaceId) -> Unit> =
    staticCompositionLocalOf { {} }

/**
 * The nearest scrolling slot above, for a gesture that has to move it.
 *
 * An editor dragging a widget toward the edge of a page has to scroll the page
 * under it, or the only way to put a widget below the fold is to drop it at the
 * edge, scroll, and drag again. The kernel owns the scroll position and the editor
 * owns the gesture, so the slot hands out this much and no more: which way it
 * moves, where it is on screen, and a way to move it.
 */
@Stable
abstract class ViewportHandle internal constructor() {
    /** Whether the view moves along x. */
    abstract val movesX: Boolean

    /** Whether the view moves along y. */
    abstract val movesY: Boolean

    /** The slot's box on screen, in window px. */
    var bounds: Rect by mutableStateOf(Rect.Zero)
        internal set

    /**
     * Moves the view on by [delta] px, positive toward the end of each axis, so the
     * content travels the other way. Says how far it actually went, which is less
     * at a scroll's end and on an axis the view does not move along.
     */
    abstract fun scrollBy(delta: Offset): Offset
}

/** A slot scrolling along one axis. */
internal class ScrollViewportHandle(private val horizontal: Boolean, private val state: ScrollState) : ViewportHandle() {
    override val movesX: Boolean get() = horizontal
    override val movesY: Boolean get() = !horizontal

    override fun scrollBy(delta: Offset): Offset {
        val moved = state.dispatchRawDelta(if (horizontal) delta.x else delta.y)
        return if (horizontal) Offset(moved, 0f) else Offset(0f, moved)
    }
}

val LocalViewport: ProvidableCompositionLocal<ViewportHandle?> = compositionLocalOf { null }

/**
 * Which axes the placement slot currently rendering has no end on, and whether it
 * reads anchors at all.
 *
 * Published by each placement slot for its own widgets, so the editor's clamp
 * holds a widget off the start of a page and lets it go as far along it as it
 * likes, the way the renderer does, and so the editor stops offering corners on a
 * map, where every widget counts from the plane's origin.
 */
@Immutable
data class PlacementBounds(val unboundedX: Boolean = false, val unboundedY: Boolean = false) {
    /** A plane with no edges reads every widget from its origin, so a named corner means nothing there. */
    val anchorsIgnored: Boolean get() = unboundedX && unboundedY

    companion object {
        val BOUNDED = PlacementBounds()
    }
}

val LocalPlacementBounds: ProvidableCompositionLocal<PlacementBounds> = compositionLocalOf { PlacementBounds.BOUNDED }

/**
 * Whether a primary-button drag on an empty part of a map moves the map.
 *
 * True for anybody looking at a map. The editor turns it off, because there a
 * press on empty space selects the slot, and the middle button moves the map.
 */
val LocalMapPanOnPrimary: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/**
 * Drawn over a map, for the way back to what is on it. [away] is true once the
 * view has been moved off the content, and [goHome] puts the content's top left
 * corner back near the view's. Nothing by default: the kernel knows a map can be
 * lost in and nothing about what a button looks like.
 */
typealias MapControls = @Composable BoxScope.(away: Boolean, goHome: () -> Unit) -> Unit

val LocalMapControls: ProvidableCompositionLocal<MapControls> = staticCompositionLocalOf { { _, _ -> } }

// Editor-only hook: SlotRenderer's placement branch reports its window bounds
// here so a palette drop can land at the release point (converted to slot-local dp).
// Default no-op; the editor host provides one that registers into the
// DropTargetRegistry.
//
// Two rects, because a slot that scrolls has two answers. [visible] is what is on
// screen, clipped to the viewport, and is what a pointer can be over. [content]
// is the whole placement box, most of it off screen, and is what a point converts
// against: a drop on a page scrolled down by a screen lands a screen down.
typealias SlotBoundsReporter = (path: SlotPath, visible: Rect, content: Rect) -> Unit

val LocalSlotBoundsReporter: ProvidableCompositionLocal<SlotBoundsReporter> =
    staticCompositionLocalOf { { _, _, _ -> } }
