package hivens.widget.model

import kotlin.reflect.KClass

// Marker interface for cross-widget service contracts. Every typed
// service that one widget exposes for sibling widgets / future mixins
// to consume extends this. The marker keeps the registry's generics
// resolvable without leaking Compose into widget-model, and lets the
// Phase E plugin loader enumerate every service contract reachable
// through reflection on loaded jars.
//
// Implementations are NOT required to be thread-safe by themselves;
// the registry's snapshot-state storage gives consumers a stable
// reference per Compose frame, and any underlying mutable state
// (StateFlow, channels, ...) is expected to handle its own threading.
interface WidgetService

// Declarative hint that a @Widget composable runtime-registers
// implementations of the listed service contracts. Documentation +
// audit only -- the annotation does NOT auto-register. The widget
// must still call provideService(...) from inside its composable
// body; this annotation lets a future tool (Phase E plugin loader,
// Phase F editor diagnostic, --audit-widgets dev tool) verify that
// every claimed service has a corresponding registration call.
//
// Multiple classes are supported because a single widget can sensibly
// expose more than one contract (e.g. a future PackController widget
// might provide both PackLifecycleService and PackTelemetryService).
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ProvidesService(
    vararg val classes: KClass<out WidgetService>,
)

// Declarative hint that a @Widget composable reads one or more
// service contracts from the registry. Same documentation /
// audit-only intent as ProvidesService. Phase F's editor diagnostic
// uses pairs of (provider, injector) to warn the user when an
// injector is dropped onto a surface that has no provider for its
// required service.
//
// vararg for symmetry with ProvidesService: a future achievement
// watcher reasonably reads MusicPlayerService AND PackLifecycleService
// in one widget, and the audit list should reflect that in a single
// annotation rather than forcing the author to chain @Repeatable
// instances.
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class InjectService(
    vararg val services: KClass<out WidgetService>,
)
