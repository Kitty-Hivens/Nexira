package hivens.widget.api

import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource

// App-provided reactive data sources widgets bind to (the read counterpart of
// the QML "bind to a service property"). Separate from WidgetServiceRegistry:
// services are widget-provided and mount/unmount-dynamic (snapshot-state,
// nullable lookups); sources are app-static, registered once at startup, and
// never absent at read time -- so this is a plain map, and a missing key is a
// wiring bug that fails loudly rather than a normal null state.
//
// Reactivity lives in each source's StateFlow, surfaced by rememberSource ->
// collectAsState; the map itself never changes after startup, so no snapshot
// state is needed here.
class WidgetDataRegistry {

    private val sources = HashMap<String, WidgetDataSource<*>>()

    fun <T> register(key: SourceKey<T>, source: WidgetDataSource<T>) {
        // Duplicate id = a wiring bug (two providers claiming one name). Unlike
        // WidgetServiceRegistry, which replaces on duplicate because providers
        // legitimately churn, app sources are registered once -- reject loudly.
        require(key.id !in sources) { "duplicate widget data source id '${key.id}'" }
        sources[key.id] = source
    }

    // Unchecked cast: register ties SourceKey<T> to WidgetDataSource<T> at the
    // call site, so the only unsound path is a hand-written raw SourceKey paired
    // with a mismatched source. Same documented seam as WidgetServiceRegistry's
    // KClass cast; the payload is often a generic (List<...>), so a runtime
    // isInstance check would be weaker than the compile-time guarantee anyway.
    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: SourceKey<T>): WidgetDataSource<T> =
        (sources[key.id] ?: error("no widget data source registered for '${key.id}'"))
            as WidgetDataSource<T>

    @Suppress("UNCHECKED_CAST")
    fun <T> find(key: SourceKey<T>): WidgetDataSource<T>? =
        sources[key.id] as WidgetDataSource<T>?

    // Enumerates registered source ids -- for a future editor "pick a source"
    // dropdown and the rule-engine's source picker.
    fun keys(): Set<String> = sources.keys.toSet()
}
