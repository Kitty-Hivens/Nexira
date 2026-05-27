package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotAddress
import hivens.widget.model.SlotPath
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

// Rendered by SlotRenderer when a slot has no widgets. Default = nothing
// (production behavior: empty slot stays invisible). The editor swaps
// in a placeholder that says "drop here" and registers the slot bounds
// with the DropTargetRegistry, making empty slots valid drop targets.
typealias EmptySlotDecorator = @Composable (address: SlotAddress) -> Unit

val LocalEmptySlotDecorator: ProvidableCompositionLocal<EmptySlotDecorator> =
    staticCompositionLocalOf { {} }

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
