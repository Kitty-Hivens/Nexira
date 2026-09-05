package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.WidgetInstance

// Locals provided once near the application root. Static because the
// graph and registry references swap on whole-tree events (layout
// reload, registry registration), not on per-frame state changes;
// staticCompositionLocalOf avoids per-read snapshot tracking cost.
val LocalLayoutGraph: ProvidableCompositionLocal<LayoutGraph> =
    staticCompositionLocalOf { LayoutGraph.EMPTY }

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

// Measured size (dp) of the current Canvas slot's content box, published by
// SlotRenderer's Canvas branch. The editor's move gesture reads it to clamp a
// free-placed widget so a grab margin always stays on-canvas (a widget can't
// be dragged fully out of reach). Zero -- the default, and outside a Canvas
// slot -- disables clamping. Dynamic (not static): it updates from onSizeChanged
// on every slot resize, and a static local would recompose the whole canvas
// subtree on each change rather than just the chrome that reads it.
val LocalCanvasSlotSizeDp: ProvidableCompositionLocal<Size> =
    compositionLocalOf { Size.Zero }

// Cube-grid cell geometry published by SlotRenderer's CubeGrid branch (dp): the
// editor's move / resize gestures read it to turn a pointer delta into a whole
// number of cells. Null outside a CubeGrid slot. Dynamic, like the size above --
// it updates as the slot is measured; only the chrome that reads it recomposes.
data class CubeGeometry(val cellWidthDp: Float, val gutterDp: Float, val columns: Int)

val LocalCubeGeometry: ProvidableCompositionLocal<CubeGeometry?> =
    compositionLocalOf { null }

// Editor-only hook: SlotRenderer's Canvas branch reports its window bounds here
// so a palette drop can land at the release point (converted to slot-local dp).
// Default no-op; the editor host provides one that registers into the
// DropTargetRegistry.
val LocalSlotBoundsReporter: ProvidableCompositionLocal<(SlotPath, Rect) -> Unit> =
    staticCompositionLocalOf { { _, _ -> } }
