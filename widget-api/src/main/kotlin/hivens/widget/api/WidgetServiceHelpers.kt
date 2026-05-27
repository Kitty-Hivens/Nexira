package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import hivens.widget.model.WidgetService
import kotlin.reflect.KClass

// Composable read of "any provider of T" from the registry. Returns
// null when no provider is currently mounted; recomposes the caller
// when a provider mounts or unmounts (SnapshotStateMap subscription).
//
// Consumer widgets should treat null as a normal state and render a
// disabled / placeholder UI, NOT throw. Service contracts come and go
// as the user edits the layout.
@Composable
inline fun <reified T : WidgetService> useService(): T? {
    val registry = LocalWidgetServiceRegistry.current
    return registry.first(T::class)
}

// Composable read of a specific provider by its widget instanceId.
// Useful when a consumer needs to bind to one particular provider
// among several (e.g. "the music player on home.new, not the one on
// the right rail"). Returns null when that exact instance is gone.
@Composable
inline fun <reified T : WidgetService> useServiceByInstance(instanceId: String): T? {
    val registry = LocalWidgetServiceRegistry.current
    return registry.byInstance(T::class, instanceId)
}

// Composable read of every provider of T. Useful for broadcast
// patterns (achievement watchers, debug inspectors) that want to
// observe state across multiple mounted providers simultaneously.
@Composable
inline fun <reified T : WidgetService> useAllServices(): List<T> {
    val registry = LocalWidgetServiceRegistry.current
    return registry.all(T::class)
}

// Register a service implementation for the lifetime of the
// surrounding composition, scoped to one widget instance.
//
// Two effects intentionally separated:
//   * `DisposableEffect(registry, clazz, instanceId)` owns the
//     registration LIFETIME -- onDispose fires only when the widget
//     unmounts (or the registry/clazz/instanceId tuple changes,
//     which in practice never happens for one widget).
//   * `SideEffect { register(...) }` refreshes the bound impl ref on
//     every successful composition. Idempotent map put; cheap.
//
// The split matters when a provider widget instantiates its impl
// inline (`provideService(MusicPlayerService::class, id,
// MusicPlayerServiceImpl(player))`) instead of wrapping in
// `remember`. With the old keyed-on-service shape every recomposition
// would unregister + register through snapshot state, and consumers
// would see a one-frame null between the two halves. Decoupling the
// effects keeps the binding live across recompositions; consumers
// see a continuous service ref even from a remember-less provider.
@Composable
fun <T : WidgetService> provideService(
    clazz: KClass<T>,
    instanceId: String,
    service: T,
) {
    val registry = LocalWidgetServiceRegistry.current
    DisposableEffect(registry, clazz, instanceId) {
        onDispose {
            registry.unregister(clazz, instanceId)
        }
    }
    SideEffect {
        registry.register(clazz, instanceId, service)
    }
}
