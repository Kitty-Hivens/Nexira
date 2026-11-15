package hivens.catalogue

import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.PackSources
import hivens.catalogue.api.ProjectSearchContribution
import hivens.catalogue.api.ProjectSearcher
import hivens.catalogue.api.SourceView
import hivens.core.data.PackOrigin
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import hivens.widget.api.ofKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap

private val log = LoggerFactory.getLogger("hivens.catalogue.Sources")

/**
 * Whether [this] is what a source's own code can throw and the catalogue answers by
 * leaving that source out: an exception, or a class the source's module cannot link, a
 * jar built against another launcher say. Cancellation is not a fault, and an error of
 * the virtual machine is not the source's to answer for.
 */
internal val Throwable.isSourceFault: Boolean
    get() = this !is CancellationException && (this is Exception || this is LinkageError)

/** A pack source opened for the app: what answers for it, and whether its search pages. */
class OpenPackSource(val listing: PackListing, val paged: Boolean)

/** A project source opened for the app: its id, and what answers for it. */
class OpenProjectSource(val id: String, val searcher: ProjectSearcher)

/**
 * The sources the modules brought in, as the catalogue lists, searches and reads from
 * them: every [PackSourceContribution] and [ProjectSearchContribution] in [taken], in the
 * order they came.
 *
 * A source is opened once for the app, the first time it is asked for, with the services
 * its own module sees ([servicesOf]), and opened again only when its module brings
 * another instance of it, a jar loaded again say, which [generation] counts. A source
 * whose module leaves leaves with it.
 *
 * A pack source is refused, and logged with the reason, when its id is one the core keeps
 * ([PackOrigin.Reserved]), one a source compiled into the launcher declares from another
 * module ([compiledIn], brought in or not), or one a module before it already brought. A
 * source whose own code throws, an exception or a class its module cannot link, is left
 * out and logged by name, so one broken module does not take the others with it.
 */
class SourceShelf(
    private val taken: StateFlow<List<TakenContribution<ModuleContribution>>>,
    /** The app level's services as the module with an id sees them. */
    private val servicesOf: (moduleId: String) -> LevelServices,
    scope: CoroutineScope,
    /** What every module compiled into the launcher carries of the source kinds, brought in or not. */
    compiledIn: List<TakenContribution<ModuleContribution>> = emptyList(),
) : PackSources {
    /** One pack source as read once off its contribution. */
    private class Declared(val source: PackSourceContribution, val view: SourceView, val paged: Boolean)

    // Keyed by the contribution itself, so a module loaded again is a new source.
    private val declared = IdentityHashMap<PackSourceContribution, Declared?>()
    private val packs = IdentityHashMap<PackSourceContribution, OpenPackSource>()
    private val projects = IdentityHashMap<ProjectSearchContribution, ProjectSearcher>()

    /** The id each pack source compiled into the launcher carries, to the module that carries it. */
    private val compiledIds: Map<String, String> = compiledIn.ofKind<PackSourceContribution>().mapNotNull { each ->
        guarded("read the id of the source of '${each.moduleId}'") { each.item.id to each.moduleId }
    }.toMap()

    /** Every pack source brought in, as a widget is shown it, as the modules change. */
    val views: StateFlow<List<SourceView>> =
        taken.map { all -> declaredOf(all).map { it.view } }.stateIn(scope, SharingStarted.Eagerly, declaredOf(taken.value).map { it.view })

    private val _generation = MutableStateFlow(0)

    /**
     * Counts the times the sources the modules bring were exchanged for other instances of
     * them: a module brought in, taken away or loaded again. What reads a source reads it
     * again on a change, so nothing goes on reading code its module's loader let go.
     */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    init {
        var last = sourcesIn(taken.value)
        scope.launch {
            taken.collect { all ->
                val now = sourcesIn(all)
                if (now.size != last.size || now.zip(last).any { (a, b) -> a !== b }) {
                    last = now
                    _generation.value += 1
                }
            }
        }
    }

    private fun sourcesIn(all: List<TakenContribution<ModuleContribution>>): List<ModuleContribution> =
        all.map { it.item }.filter { it is PackSourceContribution || it is ProjectSearchContribution }

    /** The pack source with [id], opened, or null where none is brought in, it was refused, or it could not be opened. */
    fun pack(id: String): OpenPackSource? {
        val found = declaredOf(taken.value).firstOrNull { it.view.id == id } ?: return null
        return synchronized(packs) {
            packs[found.source] ?: guarded("open the source '$id' of '${found.view.module}'") {
                OpenPackSource(found.source.open(servicesOf(found.view.module)), found.paged)
            }?.also { packs[found.source] = it }
        }
    }

    override fun listing(id: String): PackListing? = pack(id)?.listing

    /**
     * What searches projects of [type], opened: the first project source brought in that
     * searches it, or null where none does.
     */
    fun searcher(type: String): OpenProjectSource? = openSearcher { it.types.contains(type) }

    /** The first project source brought in, opened, for the lists its filters are built from. */
    fun anySearcher(): ProjectSearcher? = openSearcher { true }?.searcher

    private fun openSearcher(matches: (ProjectSearchContribution) -> Boolean): OpenProjectSource? {
        val candidates = taken.value.ofKind<ProjectSearchContribution>()
        for (each in candidates) {
            val source = each.item
            val id = guarded("read the project source of '${each.moduleId}'") { source.id.takeIf { matches(source) } } ?: continue
            val opened = synchronized(projects) {
                projects[source] ?: guarded("open the project source '$id' of '${each.moduleId}'") { source.open(servicesOf(each.moduleId)) }
                    ?.also { projects[source] = it }
            }
            if (opened != null) return OpenProjectSource(id, opened)
        }
        return null
    }

    /**
     * The pack sources in [all] as they declare themselves, the first of each id, read once
     * per contribution, and none the rules above refuse. What a module that left brought is
     * let go here, so the shelf does not hold on to the code of a jar the module list has
     * closed.
     */
    private fun declaredOf(all: List<TakenContribution<ModuleContribution>>): List<Declared> {
        val present = all.map { it.item }
        synchronized(declared) { declared.keys.retainAll { kept -> present.any { it === kept } } }
        synchronized(packs) { packs.keys.retainAll { kept -> present.any { it === kept } } }
        synchronized(projects) { projects.keys.retainAll { kept -> present.any { it === kept } } }
        val firstBy = HashMap<String, String>()
        return all.ofKind<PackSourceContribution>().mapNotNull { taken ->
            val one = synchronized(declared) {
                if (declared.containsKey(taken.item)) declared[taken.item] else declare(taken).also { declared[taken.item] = it }
            } ?: return@mapNotNull null
            val id = one.view.id
            val first = firstBy[id]
            if (first != null) {
                log.warn("The modules '{}' and '{}' both bring a source with the id '{}', the one from '{}' is left out", first, one.view.module, id, one.view.module)
                return@mapNotNull null
            }
            firstBy[id] = one.view.module
            one
        }
    }

    private fun declare(taken: TakenContribution<PackSourceContribution>): Declared? = guarded("read the source of '${taken.moduleId}'") {
        val source = taken.item
        val id = source.id
        if (PackOrigin(id) in PackOrigin.Reserved) {
            log.warn("The module '{}' brings a source with the id '{}', which the launcher keeps for itself, and it is left out", taken.moduleId, id)
            return@guarded null
        }
        val compiled = compiledIds[id]
        if (compiled != null && compiled != taken.moduleId) {
            log.warn("The module '{}' brings a source with the id '{}', which the module '{}' compiled into the launcher declares, and it is left out", taken.moduleId, id, compiled)
            return@guarded null
        }
        Declared(
            source = source,
            view = SourceView(
                id = id,
                module = taken.moduleId,
                label = source.label,
                icon = source.icon,
                filters = source.filters,
                packType = source.packType,
            ),
            paged = source.paged,
        )
    }

    private inline fun <T> guarded(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: Throwable) {
        if (!e.isSourceFault) throw e
        log.warn("Could not {}, and it is left out", what, e)
        null
    }
}
