package hivens.widget.api

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.ScopeDSL
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

private val log = LoggerFactory.getLogger("hivens.widget.api.WidgetScope")

/** How a level makes its own source for a key. Run once per level, the first time the key is read there. */
typealias SourceFactory<T> = (LevelContext) -> WidgetDataSource<T>

/** What a level hands the factory making a source in it. */
interface LevelContext {
    /** The kind of level the source is made in. */
    val level: ScopeLevel

    /** Cancelled when the level is disposed, so the work a source started here stops with it. */
    val coroutineScope: CoroutineScope

    /**
     * The level's Koin scope. A service declared for this kind of level (see
     * [levelServices]) is made once per level and closed with it, anything else
     * resolves from the levels above.
     */
    val services: Scope

    /** Another key's source as this level sees it, read at that key's own level. Null where it is missing. */
    fun <T> find(key: SourceKey<T>): WidgetDataSource<T>?
}

/**
 * One live level of the scope tree: the app, a window, a composition or a page.
 *
 * The owner of a level creates it under its parent with [child] and disposes it
 * with [close], which takes everything the level made along: its children first,
 * then the sources its factories made, its coroutines and its Koin scope. Nothing
 * here knows who the owners are, so a shell, a window registry or an outlet can
 * own levels the same way.
 *
 * A key is answered at the level it declares. [find] climbs from this level to the
 * nearest one of the key's kind and reads it there: a source the owner provided to
 * that level, the app registry's value at the app level, or else a source made on
 * the spot from the registry's factory for the key. Two levels of one kind never
 * see each other's sources, so a page cannot read its sibling's. A value a widget
 * subtree put over a level with [ProvideScoped] lives in the composition and is
 * seen by [rememberScopedSource] only, not by [find].
 *
 * Safe to read from any thread. A factory runs outside the level's lock, so it may
 * read other keys of the same level, and one that fails or reads its own key
 * leaves the key missing in this level and says so in the log once.
 */
class WidgetScope private constructor(
    override val level: ScopeLevel,
    val parent: WidgetScope?,
    private val registry: WidgetDataRegistry,
    override val services: Scope,
    context: CoroutineContext,
) : LevelContext, AutoCloseable {

    private val job = SupervisorJob(context[Job])

    override val coroutineScope: CoroutineScope = CoroutineScope(context.minusKey(Job) + job)

    private val lock = Any()

    private val made = HashMap<String, WidgetDataSource<*>>()

    private val failed = HashSet<String>()

    private val children = ArrayList<WidgetScope>()

    private var pageTable: PageLevels? = null

    // One snapshot state per key rather than one map, so a widget reading a key
    // recomposes when that key is provided or withdrawn and not on every other.
    private val provided = ConcurrentHashMap<String, MutableState<WidgetDataSource<*>?>>()

    // The keys this thread is making a source for right now, to catch a factory
    // that reads its own key before it recurses into a stack overflow.
    private val making = ThreadLocal<HashSet<String>>()

    /** True once [close] has run. A closed level answers nothing and takes no children. */
    @Volatile
    var closed: Boolean = false
        private set

    /** Creates a level of kind [level] inside this one. Its owner closes it, and this level's own close does too. */
    fun child(level: ScopeLevel): WidgetScope {
        require(level > this.level) { "a $level level cannot sit inside a ${this.level} level" }
        synchronized(lock) {
            check(!closed) { "a ${this.level} level that was disposed cannot take a $level level" }
            val own = services.getKoin().createScope(nextScopeId(level), level.servicesQualifier)
            // Koin links every new scope to its root already, so only a parent below the app needs it.
            if (!services.isRoot) own.linkTo(services)
            return WidgetScope(level, this, registry, own, coroutineScope.coroutineContext).also { children += it }
        }
    }

    /**
     * The page levels this level keeps, one per history entry, made on first use.
     * Owned here rather than by whoever draws the pages, so a router that leaves
     * the composition and comes back finds them, and none of them outlives this level.
     */
    val pages: PageLevels
        get() = synchronized(lock) {
            check(!closed) { "a ${this.level} level that was disposed keeps no pages" }
            pageTable ?: PageLevels(this).also { pageTable = it }
        }

    /** This level or the nearest one above it of kind [level], or null when there is none. */
    fun nearest(level: ScopeLevel): WidgetScope? {
        var at: WidgetScope? = this
        while (at != null) {
            if (at.level == level) return at
            if (at.level < level) return null
            at = at.parent
        }
        return null
    }

    override fun <T> find(key: SourceKey<T>): WidgetDataSource<T>? = nearest(key.level)?.sourceAt(key)

    /** Provides [value] for [key] as a constant until the returned handle is closed. See [provideSource]. */
    fun <T> provide(key: SourceKey<T>, value: T): AutoCloseable =
        provideSource(key, flowSource(MutableStateFlow(value).asStateFlow()))

    /**
     * Provides [source] for [key] in this level until the returned handle is
     * closed. For an owner outside the composition handing the level what no
     * factory could make. A subtree that builds a value for the widgets below it
     * uses [ProvideScoped] instead, which writes nothing.
     *
     * A second provision replaces the first, and closing the first one's handle
     * then leaves the second in place.
     */
    fun <T> provideSource(key: SourceKey<T>, source: WidgetDataSource<T>): AutoCloseable {
        require(key.level == level) { "widget data source '${key.id}' lives at ${key.level}, not at $level" }
        require(level != ScopeLevel.App) { "app-wide source '${key.id}' belongs in the WidgetDataRegistry" }
        require(registry.factoryFor(key) == null) { "widget data source '${key.id}' is made by its factory" }
        val slot = slotOf(key.id)
        synchronized(lock) {
            check(!closed) { "widget data source '${key.id}' provided to a $level level that was disposed" }
            slot.value = source
        }
        return AutoCloseable {
            synchronized(lock) { if (slot.value === source) slot.value = null }
        }
    }

    private fun slotOf(id: String): MutableState<WidgetDataSource<*>?> = provided.computeIfAbsent(id) { mutableStateOf(null) }

    // Unchecked casts: every path into these maps ties a SourceKey<T> to a
    // WidgetDataSource<T>, the same seam as WidgetDataRegistry.get.
    @Suppress("UNCHECKED_CAST")
    private fun <T> sourceAt(key: SourceKey<T>): WidgetDataSource<T>? {
        if (closed) return null
        (slotOf(key.id).value as WidgetDataSource<T>?)?.let { return it }
        if (level == ScopeLevel.App) registry.find(key)?.let { return it }
        val factory = registry.factoryFor(key) ?: return null
        synchronized(lock) {
            if (closed) return null
            made[key.id]?.let { return it as WidgetDataSource<T> }
            if (key.id in failed) return null
        }
        val mine = making.get() ?: HashSet<String>().also { making.set(it) }
        if (!mine.add(key.id)) {
            fail(key, IllegalStateException("the factory for '${key.id}' reads its own key"))
            return null
        }
        val fresh = try {
            factory(this)
        } catch (e: Exception) {
            fail(key, e)
            return null
        } finally {
            mine.remove(key.id)
        }
        // Another thread may have made one meanwhile. The first one stored is the
        // answer for the level's whole life, and the other is closed unseen.
        val kept = synchronized(lock) { if (closed) null else made.getOrPut(key.id) { fresh } }
        if (kept !== fresh && fresh is AutoCloseable) fresh.closeLogged("a duplicate source for '${key.id}'")
        return kept as WidgetDataSource<T>?
    }

    private fun fail(key: SourceKey<*>, cause: Exception) {
        val first = synchronized(lock) { failed.add(key.id) }
        if (first) log.error("No source for '{}' in a {} level: its factory failed", key.id, level, cause)
    }

    /**
     * Disposes this level and everything it made. Every part is attempted even
     * when one fails, and the first failure is thrown afterwards with the rest
     * attached, so one broken source cannot keep the others or the Koin scope alive.
     * An owner closing from inside a frame uses [closeLogged] instead, because a
     * throw there takes the window down.
     */
    override fun close() {
        val kids: List<WidgetScope>
        val sources: List<WidgetDataSource<*>>
        synchronized(lock) {
            if (closed) return
            closed = true
            kids = children.toList()
            children.clear()
            sources = made.values.toList()
            made.clear()
            provided.values.forEach { it.value = null }
        }
        val failures = ArrayList<Throwable>()
        // Newest first, the way they were stacked on.
        kids.asReversed().forEach { kid -> runCatching { kid.close() }.onFailure(failures::add) }
        sources.forEach { source ->
            if (source is AutoCloseable) runCatching { source.close() }.onFailure(failures::add)
        }
        job.cancel()
        // The app level borrows Koin's root scope, which lives as long as Koin does.
        if (parent != null) runCatching { services.close() }.onFailure(failures::add)
        parent?.forget(this)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }

    private fun forget(child: WidgetScope) {
        synchronized(lock) { children.remove(child) }
    }

    companion object {
        private val ids = AtomicLong()

        private fun nextScopeId(level: ScopeLevel): String = "nexira.${level.name.lowercase()}.${ids.incrementAndGet()}"

        /**
         * The root of a tree: the app level, answering app keys from [registry] and
         * the app factories in it. [services] is Koin's root scope, and [context]
         * the coroutine context every level's coroutines run under, so cancelling
         * its job stops them all.
         */
        fun app(
            registry: WidgetDataRegistry,
            services: Scope,
            context: CoroutineContext = Dispatchers.Default,
        ): WidgetScope {
            require(services.isRoot) { "the app level's services are Koin's root scope" }
            return WidgetScope(ScopeLevel.App, null, registry, services, context)
        }
    }
}

/**
 * Closes this and logs a failure instead of throwing it, for an owner that
 * closes from inside a frame and must go on to close the rest. [what] names it in
 * the log.
 */
internal fun AutoCloseable.closeLogged(what: String) {
    try {
        close()
    } catch (e: Exception) {
        log.error("Closing {} failed", what, e)
    }
}

/** The Koin scope qualifier every level of this kind creates its own scope under. */
val ScopeLevel.servicesQualifier: Qualifier get() = LEVEL_QUALIFIERS.getValue(this)

private val LEVEL_QUALIFIERS: Map<ScopeLevel, Qualifier> =
    ScopeLevel.entries.associateWith { named("nexira.level.${it.name}") }

/**
 * Declares services made once per level of kind [level], each closed with the
 * level that made it. Everything injected under such a level resolves from it.
 */
fun Module.levelServices(level: ScopeLevel, declarations: ScopeDSL.() -> Unit) {
    require(level != ScopeLevel.App) { "app-wide services are plain singles" }
    scope(level.servicesQualifier, declarations)
}
