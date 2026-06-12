package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotPath
import hivens.widget.model.WidgetChrome
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

// Paints the optional per-instance backing (WidgetChrome) around a widget --
// PRODUCTION styling, applied whenever instance.chrome != null, not just in
// edit mode. Default = identity so the kernel stays Compose-token-agnostic;
// :client-ui provides the real renderer (glass via glassSurfaceAlpha + corner
// clip + padding), so the glass color follows the active style.
typealias WidgetChromeRenderer = @Composable (chrome: WidgetChrome, content: @Composable () -> Unit) -> Unit

val LocalWidgetChromeRenderer: ProvidableCompositionLocal<WidgetChromeRenderer> =
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

// Phase G: rendered by SlotRenderer at the start of a non-empty slot in
// edit mode. Default = nothing (production: no control). The editor swaps
// in a small control that changes the slot's orientation (Column/Row/Grid)
// + grid columns. Receives the slot path + content so the control reads
// the current orientation and dispatches the mutation. Kept editor-
// agnostic here; the implementation lives in :client-ui.
typealias SlotControlDecorator = @Composable (path: SlotPath, content: SlotContent) -> Unit

val LocalSlotControlDecorator: ProvidableCompositionLocal<SlotControlDecorator> =
    staticCompositionLocalOf { { _, _ -> } }

// Phase G: rendered by SlotRenderer between two adjacent widgets in a
// Row/Column slot in edit mode. Default = nothing. The editor swaps in a
// draggable divider that redistributes weight between the widget at
// `leftIndex` and the one after it. Kept editor-agnostic here.
typealias SlotDividerDecorator = @Composable (path: SlotPath, content: SlotContent, leftIndex: Int) -> Unit

val LocalSlotDividerDecorator: ProvidableCompositionLocal<SlotDividerDecorator> =
    staticCompositionLocalOf { { _, _, _ -> } }

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

// Edit-mode slot reflow duration (ms). 0 = no animation (the production
// default, since the only provider is the editor host). While editing, the
// host supplies the active style's duration, so add / remove / resize reflow
// animates in the editor only; under Brut that resolves to ~1ms (effectively
// instant). Static is fine -- it changes only on the edit-mode toggle.
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

// Editor-only hook: SlotRenderer's Canvas branch reports its window bounds here
// so a palette drop can land at the release point (converted to slot-local dp).
// Default no-op; the editor host provides one that registers into the
// DropTargetRegistry.
val LocalSlotBoundsReporter: ProvidableCompositionLocal<(SlotPath, Rect) -> Unit> =
    staticCompositionLocalOf { { _, _ -> } }
