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
// the first time the key is read there (see WidgetScope), or declared as one the
// level's owner provides.
class WidgetDataRegistry {

    private val sources = HashMap<String, WidgetDataSource<*>>()

    private val factories = HashMap<String, Factory<*>>()

    private val declared = HashMap<String, ScopeLevel>()

    private val samples = HashMap<String, () -> Any?>()

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
        requireNew(key.id)
        sources[key.id] = source
    }

    /**
     * Registers how a level of [key]'s kind makes its own source for [key]. The
     * level runs [factory] the first time the key is read in it and disposes what
     * it made when the level itself goes.
     */
    fun <T> registerFactory(key: SourceKey<T>, factory: SourceFactory<T>) {
        requireNew(key.id)
        factories[key.id] = Factory(key.level, factory)
    }

    /**
     * Declares [key] as one the owner of each level of its kind provides, with
     * [WidgetScope.provideSource] or [ProvideScoped], because only the owner has
     * what it is made of: the screen a page was opened for, the history a
     * composition keeps. Nothing is made here. The declaration is what lets a
     * reader with no level to ask, the editor deciding where a widget may stand,
     * know the key exists and where it lives.
     */
    fun declare(key: SourceKey<*>) {
        require(key.level != ScopeLevel.App) { "app-wide source '${key.id}' is registered with a value" }
        requireNew(key.id)
        declared[key.id] = key.level
    }

    /**
     * Declares [key] as [declare] does, with a [sample] of what its owner hands over.
     *
     * A sample is display data and nothing else: what the palette draws a widget on,
     * and what the editor draws a widget on where its own data is not there. It never
     * reaches a widget drawn for use. A producer rather than a value, so each drawing
     * gets its own and nothing one of them changes leaks into another.
     */
    fun <T> declare(key: SourceKey<T>, sample: () -> T) {
        declare(key)
        samples[key.id] = sample
    }

    /** Registers a factory as [registerFactory] does, with a [sample] in the sense of [declare]. */
    fun <T> registerFactory(key: SourceKey<T>, sample: () -> T, factory: SourceFactory<T>) {
        registerFactory(key, factory)
        samples[key.id] = sample
    }

    /** The sample declared for the source [id], or null when it has none. */
    fun sampleOf(id: String): (() -> Any?)? = samples[id]

    private fun requireNew(id: String) {
        require(id !in sources && id !in factories && id !in declared) { "duplicate widget data source id '$id'" }
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

    /** Where the source [id] lives and what answers it, or null when nothing here names it. */
    fun declarationOf(id: String): KeyDeclaration? {
        if (id in sources) return KeyDeclaration(id, ScopeLevel.App, KeyKind.Source, KeyOrigin.Registered)
        factories[id]?.let { return KeyDeclaration(id, it.level, KeyKind.Source, KeyOrigin.Factory) }
        return declared[id]?.let { KeyDeclaration(id, it, KeyKind.Source, KeyOrigin.Owner) }
    }

    // Enumerates every source id, values, factories and declarations alike, for a
    // future editor "pick a source" dropdown and the rule-engine's source picker.
    fun keys(): Set<String> = sources.keys + factories.keys + declared.keys
}

/** Whether an id names a source or a command. */
enum class KeyKind { Source, Command }

/**
 * What answers a key: [Registered] one value for the whole app, [Factory] one made
 * by each level of the key's kind, [Owner] one the owner of each such level hands
 * it, which a level of that kind has only where its owner did.
 */
enum class KeyOrigin { Registered, Factory, Owner }

/** A key as the registries know it, for a reader that has only its id. */
data class KeyDeclaration(val id: String, val level: ScopeLevel, val kind: KeyKind, val origin: KeyOrigin)
