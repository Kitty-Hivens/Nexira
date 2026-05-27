package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
// surrounding composition, scoped to one widget instance. Calls
// register on enter and unregister on dispose. Keyed on (clazz,
// instanceId, service) so a service-instance swap re-registers
// cleanly without leaking the previous binding.
//
// Provider widgets call this from inside their composable body,
// typically with `service` constructed via `remember(deps) {
// MyServiceImpl(deps) }` so the impl outlives recompositions but
// dies with the widget.
@Composable
fun <T : WidgetService> provideService(
    clazz: KClass<T>,
    instanceId: String,
    service: T,
) {
    val registry = LocalWidgetServiceRegistry.current
    DisposableEffect(registry, clazz, instanceId, service) {
        registry.register(clazz, instanceId, service)
        onDispose {
            registry.unregister(clazz, instanceId)
        }
    }
}
