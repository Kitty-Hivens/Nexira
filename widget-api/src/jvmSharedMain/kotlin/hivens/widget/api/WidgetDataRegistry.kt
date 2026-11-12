package hivens.widget.api

import hivens.widget.model.ScopeLevel
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
//
// It is also the one place that says which sources exist at all. A source that
// lives below the app level cannot be a value here, there is one per window or per
// page, so it is registered as a factory that each level of the key's kind runs
// the first time the key is read there (see WidgetScope).
class WidgetDataRegistry {

    private val sources = HashMap<String, WidgetDataSource<*>>()

    private val factories = HashMap<String, Factory<*>>()

    private class Factory<T>(val level: ScopeLevel, val make: SourceFactory<T>)

    fun <T> register(key: SourceKey<T>, source: WidgetDataSource<T>) {
        // A value registered here is one for the whole app. A key of another level
        // has an answer per level, which only a factory can give.
        require(key.level == ScopeLevel.App) {
            "widget data source '${key.id}' lives at ${key.level}: register a factory for it"
        }
        // Duplicate id = a wiring bug (two providers claiming one name). Unlike
        // WidgetServiceRegistry, which replaces on duplicate because providers
        // legitimately churn, app sources are registered once -- reject loudly.
        require(key.id !in sources && key.id !in factories) { "duplicate widget data source id '${key.id}'" }
        sources[key.id] = source
    }

    /**
     * Registers how a level of [key]'s kind makes its own source for [key]. The
     * level runs [factory] the first time the key is read in it and disposes what
     * it made when the level itself goes.
     */
    fun <T> registerFactory(key: SourceKey<T>, factory: SourceFactory<T>) {
        require(key.id !in sources && key.id !in factories) { "duplicate widget data source id '${key.id}'" }
        factories[key.id] = Factory(key.level, factory)
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

    /**
     * The factory registered for [key], or null when there is none or it was
     * registered for another level, which [registerFactory] cannot catch because
     * a hand-written key may carry any level. Same cast seam as [get].
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T> factoryFor(key: SourceKey<T>): SourceFactory<T>? {
        val factory = factories[key.id] ?: return null
        return if (factory.level == key.level) factory.make as SourceFactory<T> else null
    }

    // Enumerates registered source ids, values and factories alike, for a future
    // editor "pick a source" dropdown and the rule-engine's source picker.
    fun keys(): Set<String> = sources.keys + factories.keys
}
