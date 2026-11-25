package hivens.widget.loader

import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ScreenEffect
import hivens.widget.api.WidgetApi
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.widgetPropsJson
import hivens.widget.model.ApiVersion
import hivens.widget.model.ModuleGraph
import hivens.widget.model.ModuleNode
import hivens.widget.model.ModuleWait
import hivens.widget.model.WidgetKind
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ServiceLoader
import java.util.jar.Attributes
import java.util.jar.JarFile

/** What one jar in the directory turned out to be. */
sealed interface WidgetModuleResult

/**
 * A module that loaded, with the registry it contributed.
 *
 * [loader] stays open for as long as the module does -- the registry's classes
 * come out of it -- and is exposed so a caller that wants the jar back can let
 * it go first. Windows refuses to delete or move a file that is still open, so
 * an unreleased loader there is the difference between a module the user can
 * replace and one they cannot.
 */
data class LoadedWidgetModule(
    val id: String,
    val name: String,
    val file: Path,
    val registry: WidgetRegistry,
    val loader: URLClassLoader,
    /** What it adds besides widgets: effects, themes, particle fields. None for a module that only has widgets. */
    val contributions: List<ModuleContribution> = emptyList(),
    /** The words it brought, per language, for what it declares for a person to read. */
    val strings: ModuleStrings = ModuleStrings.EMPTY,
    /** What it does not run without, as its manifest names it. */
    val requires: Map<String, ApiVersion> = emptyMap(),
    /** The packages of each API it publishes, by API id. */
    val published: Map<String, Set<String>> = emptyMap(),
) : WidgetModuleResult {
    /** The screen effects among [contributions]. */
    val effects: List<ScreenEffect> get() = contributions.filterIsInstance<ScreenEffect>()
}

/**
 * A module the caller asked not to load. Its manifest was read, so it can be named
 * and offered back, and no class loader was opened for it.
 */
data class DisabledWidgetModule(
    val id: String,
    val name: String,
    val file: Path,
    /** What another module could require it by, if it were on. */
    val offers: Map<String, ApiVersion> = emptyMap(),
) : WidgetModuleResult

/**
 * A module that is as it should be and waits, with every reason: something it requires is not
 * there, is switched off, is in another version or does not run itself. No class loader was
 * opened for it.
 */
data class WaitingWidgetModule(
    val id: String,
    val name: String,
    val file: Path,
    val reasons: List<ModuleWait>,
) : WidgetModuleResult

/** A jar in the directory that did not become a module, and why. */
data class RejectedWidgetModule(
    val file: Path,
    val reason: String,
) : WidgetModuleResult

/** Everything one pass over the directory found. */
data class WidgetModuleScan(
    val loaded: List<LoadedWidgetModule> = emptyList(),
    val rejected: List<RejectedWidgetModule> = emptyList(),
    val disabled: List<DisabledWidgetModule> = emptyList(),
    val waiting: List<WaitingWidgetModule> = emptyList(),
    /** The jars of the directory and what the launcher offers, by what they require of each other. */
    val graph: ModuleGraph = ModuleGraph.EMPTY,
)

/**
 * Finds widget modules in a directory and loads the registries they carry.
 *
 * A module is a jar. There is no install step and no database: the file is there
 * or it is not, which is the model people already have for game mods and the one
 * thing about mod loading nobody needs explained.
 *
 * Each module gets its own [URLClassLoader], and its parent is a view of the
 * application that answers only what a module is meant to compile against. Those
 * names are parent-first, which is load-bearing: a widget is a composable, and a
 * composable compiled against a second copy of compose-runtime would hand the wrong
 * Composer type across every call. A module that bundles its own copy of something
 * shared simply gets the launcher's. Everything else the launcher carries is not
 * visible from the jar at all: a name outside the shared set is looked up in the jar
 * and nowhere else, so a jar that bundles a library the launcher also uses gets its
 * own copy, which knows nothing of the launcher's.
 *
 * The shared set, which is all a jar sees of the application besides the JDK:
 *
 * - the widget kernel, `hivens.widget.api` and `hivens.widget.model`;
 * - the API artifact of each module API the jar names in its manifest's
 *   `Nexira-Module-Requires` or `Nexira-Module-Uses`, by the packages [apiPackages] gives
 *   for that API id (`hivens.packs.api` for `nexira.packs`, `hivens.accounts.api` for
 *   `nexira.accounts`), or from the jar that publishes it. An API the jar does not name is
 *   not visible to it, so what it compiles against is what the version check below has
 *   checked, and one it only uses is visible only in a version that serves;
 * - Compose: `androidx` (Compose and the Jetpack libraries it is built on),
 *   `org.jetbrains.compose`, and Skiko, `org.jetbrains.skia` and `org.jetbrains.skiko`;
 * - the Kotlin standard library, `kotlin`, kotlinx coroutines and serialization, and slf4j;
 * - the media engine, `dev.hivens.skinema`, whose native libraries a process can
 *   load once and which the pixel player example plays through;
 * - the design system, nx-ui, by artifact and not by package: its classes are shared
 *   one by one from the list its build writes ([SHARED_ARTIFACTS], [SharedClassList]),
 *   because `hivens.ui.theme` and `hivens.ui.nx` also hold classes of the launcher's
 *   own interface, which stay hidden. nx-ui is therefore part of what modules compile
 *   against, as the kernel is: a change that breaks its public API breaks the modules
 *   built on it;
 * - the colour library, `com.materialkolor`.
 *
 * Every other library stays hidden. A module that uses one, an HTTP client or an
 * image loader the launcher also carries, bundles it in its jar and gets its own copy.
 *
 * Not visible: the launcher itself (`hivens.ui` outside the kernel and the design
 * system, `hivens.auth`, `hivens.accounts` and `hivens.packs` outside their API
 * packages, `hivens.launcher`, `hivens.core` and the rest), ui-layers, which no public
 * API a module compiles against names, this loader, and Koin, so a jar can neither name
 * the launcher's services nor reach its dependency graph. Resources follow the same
 * rule by the package of their path. One loader per module also keeps modules from
 * seeing each other beyond what one publishes as an API and the other names, so a name
 * collision between two of them is not a way to hijack a third.
 *
 * While this loader runs a module's code, its constructors and the reads of what it
 * declares, the thread's context class loader is the module's own loader, so a
 * library the jar bundles that finds its providers through it (a `ServiceLoader` call
 * that names no loader) finds the jar's own. The widget kernel does the same around a
 * module's key declaration, factories, samples and service makers. It cannot be done
 * where module code runs inside the composition or on a coroutine the module started:
 * a widget drawing, an effect applying, a flow a factory returned being collected.
 * There the context class loader is whatever the thread has, so such a library is
 * handed its loader explicitly or finds the launcher's providers.
 *
 * This closes the paths a module can take by naming things. It is not a sandbox: a
 * jar runs with whatever access the JVM has, and code determined to reach past this
 * with reflection or class loading machinery of its own is outside what a loader can
 * hold back.
 *
 * [shadowDir], when given, is where each jar is copied before it is opened, under
 * a name made of its contents. The loader then holds the copy and never the file
 * the person put there, so that file can be replaced or deleted while the launcher
 * runs, which is what loading modules without a restart needs. It also keeps a
 * replaced jar from being read through a stale handle: the JDK caches open jars by
 * path, and a new loader over the same path can be handed the old one.
 *
 * [disabled] names modules to leave closed. Their manifest is read so they can be
 * listed and switched back on, and nothing else is.
 *
 * What a jar requires is resolved before any is opened (see [ModuleGraph]), over the jars
 * and what the launcher offers: [offered] is every module API this launcher carries and
 * every module of its own that can be required by its id, at the version it carries. A jar
 * that requires something nothing offers, or that is offered in a version that does not
 * serve the one it names, another major or an older minor, waits with that reason, and so
 * does one whose requirement is switched off or waits itself. The jars are opened in the
 * order the graph gives, so a jar finds what it requires already there.
 *
 * A jar publishes APIs of its own too (`Nexira-Module-Provides`): the jars that name one are
 * shown its packages, read through the loader of the jar that publishes it. An API the
 * launcher or an earlier jar already offers is not published twice, and the later jar is
 * refused, as is one that publishes packages the launcher shares, since a jar would be shown
 * the launcher's.
 *
 * [apiPackages] is the packages each offered API's artifact lives in, by API id, which a jar
 * that names the API is shown, and none for a module offered by its id alone. A jar that
 * requires something offered here that is not in it is refused, since it would load and fail
 * on the first class it names.
 *
 * [sharedArtifacts] is the artifacts shared class by class, read off [parent].
 *
 * [contributionKinds] is the kinds of contribution read out of each jar: the kernel's own
 * ([ModuleContribution.kinds]) and the ones the APIs of the modules compiled into the
 * launcher declare, a theme the themes module takes say. Such a kind lives in its API's
 * package, so only a jar that names the API sees it, and a jar that carries one without
 * naming the API is refused before any of its classes loads, with a reason that says to name
 * the API.
 */
class WidgetModuleLoader(
    private val directory: Path,
    private val parent: ClassLoader = WidgetModuleLoader::class.java.classLoader,
    private val shadowDir: Path? = null,
    private val disabled: Set<String> = emptySet(),
    private val contributionKinds: List<Class<out ModuleContribution>> = ModuleContribution.kinds,
    private val offered: Map<String, ApiVersion> = emptyMap(),
    private val apiPackages: Map<String, Set<String>> = emptyMap(),
    private val sharedArtifacts: List<String> = SHARED_ARTIFACTS,
) {

    private val log = LoggerFactory.getLogger(WidgetModuleLoader::class.java)

    /**
     * The classes of [sharedArtifacts], from the lists their builds wrote. An artifact
     * the application carries no list for shares nothing, and says so once.
     */
    private val sharedClasses: Set<String> by lazy {
        sharedArtifacts.flatMap { artifact ->
            SharedClassList.read(parent, artifact) ?: emptySet<String>().also {
                log.warn("No list of the classes of '{}' at {}: widget modules see none of them", artifact, SharedClassList.pathOf(artifact))
            }
        }.toSet()
    }

    fun scan(): WidgetModuleScan {
        if (!Files.isDirectory(directory)) {
            log.info("No widget module directory at {} -- nothing to load", directory)
            return WidgetModuleScan()
        }

        val jars = runCatching {
            Files.list(directory).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                    // Stable order so a shadowed id is decided by the file name
                    // rather than by whatever the filesystem happened to return.
                    .sorted()
                    .toList()
            }
        }.getOrElse {
            log.warn("Could not read the widget module directory at {}", directory, it)
            return WidgetModuleScan()
        }

        if (jars.isEmpty()) {
            report(emptyList(), emptyList())
            return WidgetModuleScan()
        }
        val rejected = mutableListOf<RejectedWidgetModule>()
        val off = mutableListOf<DisabledWidgetModule>()
        val found = mutableListOf<Candidate>()
        jars.forEach { jar ->
            val read = read(jar)
            read.candidate?.let { found += it }
            read.off?.let { off += it }
            read.fault?.let { rejected += RejectedWidgetModule(jar, it) }
        }
        val offering = publishersAmong(found, rejected)
        val graph = ModuleGraph.of(
            offered.map { (name, version) -> ModuleNode(name, mapOf(name to version)) } +
                offering.distinctBy { it.id }.map { it.node } +
                off.map { ModuleNode(it.id, it.offers, off = true) },
        )

        val loaded = LinkedHashMap<String, LoadedWidgetModule>()
        val waiting = mutableListOf<WaitingWidgetModule>()
        val byId = offering.groupBy { it.id }
        // The order is of modules that could run. One that then does not load leaves what requires it waiting.
        val failed = HashSet<String>()
        graph.order.forEach { id ->
            val candidates = byId[id] ?: return@forEach
            val first = candidates.first()
            val stopped = first.requires.keys.mapNotNull { name -> graph.providerOf(name)?.takeIf { it in failed }?.let { ModuleWait.Stopped(name, it) } }
            if (stopped.isNotEmpty()) {
                waiting += WaitingWidgetModule(id, first.name, first.jar, stopped)
                failed += id
            } else {
                // A second jar of the same id that asks and offers the same stands in for a first that does not load.
                candidates.forEach { candidate ->
                    if (id in loaded || candidate !== first && !candidate.declaresLike(first)) return@forEach
                    when (val result = load(candidate, graph, loaded)) {
                        is LoadedWidgetModule -> loaded[id] = result
                        is RejectedWidgetModule -> rejected += result
                        else -> Unit
                    }
                }
                if (id !in loaded) failed += id
            }
            rejected += repeats(candidates, loaded[id]?.file ?: first.jar, rejected)
        }
        graph.waiting.forEach { (id, reasons) ->
            val candidates = byId[id] ?: return@forEach
            val first = candidates.first()
            waiting += WaitingWidgetModule(id, first.name, first.jar, reasons)
            rejected += repeats(candidates, first.jar, rejected)
        }

        val modules = loaded.values.toList()
        report(modules, rejected)
        waiting.forEach { log.warn("Widget module '{}' ({}) waits: {}", it.id, it.file.fileName, it.reasons.joinToString("; ", transform = ::said)) }
        off.forEach { log.info("Widget module '{}' ({}) is switched off and was not loaded", it.id, it.file.fileName) }
        return WidgetModuleScan(modules, rejected, off, waiting, graph)
    }

    /** [candidates] of one id that are not [kept] and have no reason of their own in [rejected] yet. */
    private fun repeats(candidates: List<Candidate>, kept: Path, rejected: List<RejectedWidgetModule>): List<RejectedWidgetModule> =
        candidates.filter { candidate -> candidate.jar != kept && rejected.none { it.file == candidate.jar } }.map {
            // Settings, the on and off switch and the crash record are all kept by id, so
            // two jars sharing one would be switched and set as a single module.
            RejectedWidgetModule(it.jar, "its id '${it.id}' is already carried by ${kept.fileName}")
        }

    /**
     * Those of [found] whose APIs are theirs to publish, in their order. Of two jars that offer one name
     * the first does, and one that offers what the launcher does, or shares its packages, goes to [rejected].
     */
    private fun publishersAmong(found: List<Candidate>, rejected: MutableList<RejectedWidgetModule>): List<Candidate> {
        val taken = HashMap<String, Candidate>()
        return found.filter { candidate ->
            val fault = candidate.node.offers.keys.firstNotNullOfOrNull { name ->
                val other = taken[name]
                when {
                    name in offered -> "offers '$name', which this launcher offers"
                    other != null && other.id != candidate.id -> "offers '$name', which ${other.jar.fileName} already offers"
                    else -> null
                }
            } ?: candidate.apiPackages.values.flatten().firstNotNullOfOrNull { pkg ->
                val shared = (SHARED_PACKAGES + apiPackages.values.flatten()).firstOrNull { it.covers(pkg) || pkg.covers(it) }
                shared?.let { "publishes the package '$pkg', and the launcher shares '$it' with every module" }
            }
            if (fault != null) {
                rejected += RejectedWidgetModule(candidate.jar, fault)
                false
            } else {
                candidate.node.offers.keys.forEach { name -> if (name !in taken) taken[name] = candidate }
                true
            }
        }
    }

    /** A jar whose manifest is a module's, before anything of it is opened. */
    private class Candidate(
        val jar: Path,
        val id: String,
        val name: String,
        val node: ModuleNode,
        /** The packages of each API the jar publishes. */
        val apiPackages: Map<String, Set<String>>,
    ) {
        val requires: Map<String, ApiVersion> get() = node.requires
        val uses: Map<String, ApiVersion> get() = node.uses

        /** Whether [other] asks for and offers the same. */
        fun declaresLike(other: Candidate): Boolean =
            node.offers == other.node.offers && requires == other.requires && uses == other.uses && apiPackages == other.apiPackages
    }

    /** What a jar's manifest makes of it: a module to load, one switched off, or neither and why. */
    private class Read(val candidate: Candidate? = null, val off: DisabledWidgetModule? = null, val fault: String? = null)

    private fun read(jar: Path): Read {
        val manifest = runCatching {
            JarFile(jar.toFile()).use { it.manifest }
        }.getOrElse { return Read(fault = "not a readable jar: ${it.message}") }
            ?: return Read(fault = "no manifest")

        val attributes = manifest.mainAttributes
        val declared = attributes.getValue(WidgetApi.MANIFEST_VERSION)
            ?: return Read(fault = "no ${WidgetApi.MANIFEST_VERSION} in the manifest -- not a widget module")
        val version = declared.trim().toIntOrNull()
            ?: return Read(fault = "${WidgetApi.MANIFEST_VERSION} is '$declared', which is not a version number")
        if (version != WidgetApi.VERSION) {
            // Refusing is the feature. A module built against another ABI may
            // link and then misbehave, and a widget that draws the wrong thing
            // is harder to diagnose than one that never appears with a reason.
            return Read(fault = "built for widget API $version, this launcher speaks ${WidgetApi.VERSION}")
        }

        val id = attributes.getValue(WidgetApi.MANIFEST_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return Read(fault = "no ${WidgetApi.MANIFEST_ID} in the manifest")
        val name = attributes.getValue(WidgetApi.MANIFEST_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: id

        val declarations = declarationsOf(id, attributes)
        // What it offers is read all the same, so what requires it is told it is off and not that it is missing.
        if (id in disabled) return Read(off = DisabledWidgetModule(id, name, jar, declarations.node?.offers.orEmpty()))
        // Its words, its settings and what is required by its id would be taken for the launcher's own module's.
        if (id in offered) return Read(fault = "its id '$id' is one of the launcher's own modules'")
        val node = declarations.node ?: return Read(fault = declarations.fault)
        (unsharedApi(node) ?: unnamedApiFault(jar, node.requires.keys + node.uses.keys))?.let { return Read(fault = it) }
        return Read(Candidate(jar, id, name, node, declarations.apiPackages))
    }

    /** What a manifest says of other modules, or why it cannot be read. */
    private class Declarations(val node: ModuleNode?, val fault: String = "", val apiPackages: Map<String, Set<String>> = emptyMap())

    private fun declarationsOf(id: String, attributes: Attributes): Declarations {
        fun fault(reason: String) = Declarations(null, reason)
        fun versions(attribute: String): Map<String, ApiVersion>? = attributes.getValue(attribute).let { if (it == null) emptyMap() else ApiVersion.parseAll(it) }
        fun notAList(attribute: String) = fault("$attribute is '${attributes.getValue(attribute)}', which is not a list of id=major.minor")

        val requires = versions(WidgetApi.MANIFEST_REQUIRES) ?: return notAList(WidgetApi.MANIFEST_REQUIRES)
        val uses = versions(WidgetApi.MANIFEST_USES) ?: return notAList(WidgetApi.MANIFEST_USES)
        (requires.keys intersect uses.keys).firstOrNull()?.let {
            return fault("names '$it' both in ${WidgetApi.MANIFEST_REQUIRES} and in ${WidgetApi.MANIFEST_USES}")
        }
        val provides = versions(WidgetApi.MANIFEST_PROVIDES) ?: return notAList(WidgetApi.MANIFEST_PROVIDES)
        val ownText = attributes.getValue(WidgetApi.MANIFEST_MODULE_VERSION)
        val own = ownText?.let { ApiVersion.parse(it) ?: return fault("${WidgetApi.MANIFEST_MODULE_VERSION} is '$it', which is not major.minor") }
        if (own != null && provides[id] != null && provides[id] != own) {
            return fault("is at $own by ${WidgetApi.MANIFEST_MODULE_VERSION} and offers its own id at ${provides[id]}")
        }
        val packages = LinkedHashMap<String, MutableSet<String>>()
        attributes.getValue(WidgetApi.MANIFEST_API_PACKAGES)?.split(',')?.filter { it.isNotBlank() }?.forEach { entry ->
            val api = entry.substringBefore('=', missingDelimiterValue = "").trim()
            val pkg = entry.substringAfter('=').trim()
            if (api.isEmpty() || pkg.isEmpty()) return fault("${WidgetApi.MANIFEST_API_PACKAGES} has '${entry.trim()}', which is not id=package")
            if (api !in provides) return fault("gives a package for '$api', which is not in ${WidgetApi.MANIFEST_PROVIDES}")
            packages.getOrPut(api) { LinkedHashSet() } += pkg
        }
        (provides.keys - packages.keys).firstOrNull()?.let { return fault("offers the API '$it' and names no package for it in ${WidgetApi.MANIFEST_API_PACKAGES}") }
        val offers = if (own == null) provides else provides + (id to own)
        return Declarations(ModuleNode(id, offers, requires, uses), apiPackages = packages)
    }

    /** Why [node] cannot be shown an API it requires of the launcher, or null when it can be shown every one. */
    private fun unsharedApi(node: ModuleNode): String? =
        node.requires.keys.firstOrNull { it in offered && it !in apiPackages }
            ?.let { "built against the module API '$it', which this launcher carries without sharing its classes" }

    /** Opens [candidate]'s jar, which [graph] lets run, over the modules in [running] it names. */
    private fun load(candidate: Candidate, graph: ModuleGraph, running: Map<String, LoadedWidgetModule>): WidgetModuleResult {
        val jar = candidate.jar
        val id = candidate.id
        val opened = runCatching { shadowCopy(jar) }.getOrElse {
            return RejectedWidgetModule(jar, "could not be copied aside to load: ${it.message}")
        }

        // What it is shown of the APIs it names: those it requires, which are there, and those it uses
        // where they are there in a version that serves.
        val shown = candidate.requires.keys + candidate.uses.filter { (name, builtAgainst) -> graph.serves(name, builtAgainst) }.keys
        val published = shown.mapNotNull { name ->
            val publisher = graph.providerOf(name)?.let(running::get) ?: return@mapNotNull null
            publisher.published[name]?.let { Published(it, publisher.loader) }
        }

        // From here the jar is held open by the loader. Every path that does not
        // hand it to a LoadedWidgetModule has to let it go again: a rejected jar
        // whose loader outlives the scan is a file the user cannot delete for the
        // rest of the session, and on Windows cannot replace either.
        //
        // Named after the module, which is what lets a crash be traced back to it:
        // every frame of a stack trace carries the name of the loader its class came
        // from. See [moduleIdIn].
        val view = ModuleClassView(VIEW_PREFIX + id, parent, visiblePackages(shown), sharedClasses, published)
        val loader = URLClassLoader(LOADER_PREFIX + id, arrayOf(opened.toUri().toURL()), view)
        // Module code runs from here, its constructors and the reads of what it declares, so
        // the thread's context class loader is the module's own: a library the jar bundles
        // that looks its providers up through it finds the jar's, not the launcher's.
        return withContextLoader(loader) {
            val registries = runCatching {
                ServiceLoader.load(WidgetRegistry::class.java, loader)
                    // ServiceLoader walks the whole delegation chain, so without this
                    // every module would also "find" the launcher's own built-in
                    // registry and contribute a second copy of it.
                    .filter { it.javaClass.classLoader === loader }
            }.getOrElse {
                loader.release()
                return RejectedWidgetModule(jar, "could not instantiate its registry: ${it.message}")
            }

            val contributions = mutableListOf<ModuleContribution>()
            val byKind = mutableListOf<List<ModuleContribution>>()
            // A kind of an API the jar only uses, which is not there for it, is one it does without.
            contributionKinds.filter { kind -> apiOwning(kind).let { it == null || it !in candidate.uses || it in shown } }.forEach { kind ->
                val found = runCatching {
                    ServiceLoader.load(kind, loader).filter { it.javaClass.classLoader === loader }
                }.getOrElse {
                    loader.release()
                    return RejectedWidgetModule(jar, "could not instantiate its ${kind.simpleName} services: ${it.message}")
                }
                byKind += found
                contributions += found
            }

            when {
                // A module may carry nothing but effects: it is a module all the same, with no widgets.
                // So is one that only publishes an API for others to compile against.
                registries.isEmpty() && contributions.isEmpty() && candidate.apiPackages.isEmpty() -> {
                    loader.release()
                    RejectedWidgetModule(jar, "declares the widget API but carries neither a registry nor a contribution service")
                }
                registries.size > 1 -> {
                    // The processor emits exactly one per module. More than one means a
                    // hand-assembled or merged jar, where which registry wins is not
                    // something this can decide for the author.
                    loader.release()
                    RejectedWidgetModule(jar, "carries ${registries.size} registries; a module must carry one")
                }
                else -> {
                    // Listed once here, where a failure is this module's alone. Everything
                    // downstream reads the list outside any guard, building the registry
                    // the whole shell resolves from, so a registry that throws there took
                    // the shell down on every start instead of being refused by name.
                    val registry = registries.singleOrNull() ?: NoWidgets
                    val listed = runCatching { registry.all().values.toList() }.getOrElse {
                        loader.release()
                        return RejectedWidgetModule(jar, "its registry failed to list its widgets: ${it.message ?: it.javaClass.simpleName}")
                    }
                    // The runtime half of the compile-time validator, for what a built
                    // descriptor still shows. A module is someone else's build, and
                    // nothing guarantees it went through the processor that checks these.
                    listed.firstNotNullOfOrNull { descriptorFault(it) }?.let { fault ->
                        loader.release()
                        return RejectedWidgetModule(jar, fault)
                    }
                    byKind.firstNotNullOfOrNull { ModuleContribution.faultIn(it) { read -> guarded(read) } }?.let { fault ->
                        loader.release()
                        return RejectedWidgetModule(jar, fault)
                    }
                    LoadedWidgetModule(id, candidate.name, jar, registry, loader, contributions, readStrings(opened, id), candidate.requires, candidate.apiPackages)
                }
            }
        }
    }

    /**
     * The language files of a module, read from the jar itself, so what a module says is only ever
     * its own words and never a file of the same name elsewhere on the application's class path:
     * every `i18n/<tag>.properties` for its own words, every `i18n/<namespace>/<tag>.properties`
     * for words it carries for another namespace, a translation of the launcher's own say, and every
     * `i18n/<tag>/language.properties` for a language it adds. A file that cannot be read costs the
     * module that file and nothing more.
     */
    private fun readStrings(jar: Path, id: String): ModuleStrings {
        val tables = HashMap<String, Map<String, String>>()
        val namespaces = HashMap<String, MutableMap<String, Map<String, String>>>()
        val languages = HashMap<String, Map<String, String>>()
        runCatching {
            JarFile(jar.toFile()).use { file ->
                file.entries().asSequence().forEach { entry ->
                    val own = STRINGS_FILE.matchEntire(entry.name)
                    val language = LANGUAGE_FILE.matchEntire(entry.name)
                    val other = if (language == null) NAMESPACED_STRINGS_FILE.matchEntire(entry.name) else null
                    if (own == null && other == null && language == null) return@forEach
                    runCatching {
                        val words = file.getInputStream(entry).use(ModuleStrings::read)
                        if (own != null) {
                            tables[own.groupValues[1]] = words
                        } else if (language != null) {
                            languages[language.groupValues[1]] = words
                        } else if (other != null) {
                            namespaces.getOrPut(other.groupValues[1]) { HashMap() }[other.groupValues[2]] = words
                        }
                    }.onFailure { log.warn("Widget module '{}' has an unreadable language file {}: {}", id, entry.name, it.message) }
                }
            }
        }.onFailure { log.warn("Widget module '{}' language files could not be read: {}", id, it.message) }
        return if (tables.isEmpty() && namespaces.isEmpty() && languages.isEmpty()) ModuleStrings.EMPTY else ModuleStrings(tables, namespaces, languages)
    }

    /**
     * Why a jar that carries services of a kind of contribution a module API declares, without
     * naming that API in [named], cannot work, or null when it names every such API. Read off
     * the jar's service files before any of its classes is loaded, so the reason says what to
     * change in the manifest rather than which class could not be linked. A jar that cannot be
     * listed is left to the reads that follow, which refuse it with their own reason.
     */
    private fun unnamedApiFault(jar: Path, named: Set<String>): String? {
        val owners = contributionKinds.mapNotNull { kind -> apiOwning(kind)?.let { kind to it } }
        if (owners.isEmpty()) return null
        val services = runCatching {
            JarFile(jar.toFile()).use { file ->
                file.entries().asSequence().map { it.name }.filter { it.startsWith(SERVICES_DIR) }.map { it.removePrefix(SERVICES_DIR) }.toSet()
            }
        }.getOrElse { return null }
        val (kind, api) = owners.firstOrNull { (kind, api) -> kind.name in services && api !in named } ?: return null
        return "carries ${kind.simpleName} services, which come from the module API '$api': name $api in ${WidgetApi.MANIFEST_REQUIRES}"
    }

    /** The module API whose packages hold [kind], or null for a kind of the kernel's own. */
    private fun apiOwning(kind: Class<*>): String? {
        val pkg = kind.packageName
        return apiPackages.entries.firstOrNull { (_, packages) -> packages.any { it.covers(pkg) } }?.key
    }

    /** Runs [block] with [loader] as this thread's context class loader, and puts the one before back. */
    private inline fun <T> withContextLoader(loader: ClassLoader, block: () -> T): T {
        val thread = Thread.currentThread()
        val before = thread.contextClassLoader
        thread.contextClassLoader = loader
        try {
            return block()
        } finally {
            thread.contextClassLoader = before
        }
    }

    /** The packages of the launcher a jar that is shown the APIs [shown] sees, see the class KDoc. */
    private fun visiblePackages(shown: Set<String>): Set<String> =
        SHARED_PACKAGES + shown.flatMap { apiPackages[it].orEmpty() }

    /** [reason] for the log. */
    private fun said(reason: ModuleWait): String = when (reason) {
        is ModuleWait.Missing -> "it requires '${reason.name}' ${reason.needed}, which nothing here offers"
        is ModuleWait.OtherVersion -> "it requires '${reason.name}' ${reason.needed}, and what is here is ${reason.here}"
        is ModuleWait.Off -> "it requires '${reason.name}', and the module '${reason.module}' is switched off"
        is ModuleWait.Stopped -> "it requires '${reason.name}', and the module '${reason.module}' does not run"
        is ModuleWait.Cycle -> "it requires itself: ${reason.modules.joinToString(" -> ")} -> ${reason.modules.first()}"
    }

    /** What a module with no widgets contributes to the registry. */
    private object NoWidgets : WidgetRegistry {
        override fun all(): Map<WidgetKind, WidgetDescriptor> = emptyMap()
        override fun get(kind: WidgetKind): WidgetDescriptor? = null
    }

    /**
     * Why [descriptor] cannot work as declared, or null when it can.
     *
     * Props whose own defaults do not decode through their serializer open an empty
     * panel with nothing saying why, and a declared plane beside drawsOwnSurface is
     * two claims that cannot both hold. The processor refuses both at build time.
     *
     * Every read is guarded, a linkage error included: the generated registry
     * resolves its props serializer and its plane lazily, so a class the module
     * needs and does not carry, or a plane this build does not know, surfaces
     * here. Thrown on, it failed the whole scan and the shell with it, on every
     * start, over one module that is now rejected instead.
     */
    private fun descriptorFault(descriptor: WidgetDescriptor): String? {
        val kind = guarded { descriptor.kind.value }.getOrElse { return "a widget's kind could not be read: ${it.message}" }
        val serializer = guarded { descriptor.propsSerializer }
            .getOrElse { return "widget '$kind' has a props class that could not be loaded: ${it.message}" }
        if (serializer != null) {
            val decodes = guarded { widgetPropsJson.decodeFromJsonElement(serializer, descriptor.defaultPropsJson) }.isSuccess
            if (!decodes) return "widget '$kind' has default props its own props class cannot read"
        }
        val surface = guarded { descriptor.defaultSurface }
            .getOrElse { return "widget '$kind' declares a surface this build cannot read: ${it.message}" }
        val ownSurface = guarded { descriptor.drawsOwnSurface }.getOrElse { return "widget '$kind' could not be read: ${it.message}" }
        if (surface != null && ownSurface) {
            return "widget '$kind' declares a surface and drawsOwnSurface together"
        }
        return null
    }

    /** [block]'s value, or its failure, a linkage error counted among failures. */
    private inline fun <T> guarded(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Result.failure(e)
    } catch (e: LinkageError) {
        Result.failure(e)
    }

    /**
     * Where the jar is opened from: the jar itself, or a copy of it named by its
     * contents. A copy that is already there is the same bytes and is reused.
     */
    private fun shadowCopy(jar: Path): Path {
        val dir = shadowDir ?: return jar
        Files.createDirectories(dir)
        val digest = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(jar).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val name = digest.digest().joinToString("") { "%02x".format(it) } + ".jar"
        val copy = dir.resolve(name)
        if (!Files.exists(copy)) {
            val partial = dir.resolve("$name.part")
            Files.copy(jar, partial, StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial, copy, StandardCopyOption.ATOMIC_MOVE)
        }
        return copy
    }

    /** Closing a loader can throw; a jar we have already refused is not worth a failed scan. */
    private fun URLClassLoader.release() {
        runCatching { close() }.onFailure { log.debug("could not close a refused module's loader", it) }
    }

    companion object {
        /** What a module's class loader is named, ahead of the module's id. */
        const val LOADER_PREFIX = "widget-module:"

        /** What the view of the application a module's loader sits on is named, ahead of the module's id. */
        private const val VIEW_PREFIX = "widget-module-view:"

        /**
         * The packages of the application every jar is shown, with the packages below
         * them, whatever it names. The API artifacts a jar names come on top, see the
         * class KDoc for the whole set.
         */
        val SHARED_PACKAGES: Set<String> = setOf(
            "hivens.widget.api",
            "hivens.widget.model",
            "androidx",
            "org.jetbrains.compose",
            "org.jetbrains.skia",
            "org.jetbrains.skiko",
            "kotlin",
            "kotlinx.coroutines",
            "kotlinx.serialization",
            "org.slf4j",
            "dev.hivens.skinema",
            "com.materialkolor",
        )

        /**
         * The artifacts of the application every jar is shown class by class, from the
         * list each one's build writes (see [SharedClassList]): the design system, nx-ui,
         * whose packages the launcher's own interface also uses.
         */
        val SHARED_ARTIFACTS: List<String> = listOf("nx-ui")

        private const val SERVICES_DIR = "META-INF/services/"

        private val STRINGS_FILE = Regex("""i18n/([A-Za-z][A-Za-z0-9_-]*)\.properties""")

        private val LANGUAGE_FILE = Regex("""i18n/([A-Za-z][A-Za-z0-9_-]*)/language\.properties""")

        private val NAMESPACED_STRINGS_FILE = Regex("""i18n/([A-Za-z0-9][A-Za-z0-9._-]*)/([A-Za-z][A-Za-z0-9_-]*)\.properties""")

        /**
         * The module whose code is on [error]'s stack, or null when none is.
         *
         * Reads the loader name every stack frame carries, through the causes and
         * the suppressed exceptions too, because a widget's failure usually reaches
         * the shell wrapped by Compose. The frame nearest the top wins: that is the
         * module whose code was running when it went wrong, rather than one it was
         * merely called through.
         */
        fun moduleIdIn(error: Throwable): String? {
            val seen = HashSet<Throwable>()
            val queue = ArrayDeque<Throwable>().apply { add(error) }
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                if (!seen.add(t)) continue
                t.stackTrace.firstNotNullOfOrNull { frame ->
                    frame.classLoaderName?.takeIf { it.startsWith(LOADER_PREFIX) }?.removePrefix(LOADER_PREFIX)
                }?.let { return it }
                t.cause?.let(queue::add)
                t.suppressed.forEach(queue::add)
            }
            return null
        }
    }

    private fun report(loaded: List<LoadedWidgetModule>, rejected: List<RejectedWidgetModule>) {
        if (loaded.isEmpty() && rejected.isEmpty()) {
            log.info("Widget modules: none in {}", directory)
            return
        }
        loaded.forEach { module ->
            val kinds = module.registry.all().keys.map { it.value }.sorted()
            log.info(
                "Widget module '{}' ({}) loaded from {} with {} widget(s): {}",
                module.id, module.name, module.file.fileName, kinds.size, kinds.joinToString(", "),
            )
        }
        // Warn, not debug: a module the user put there on purpose and that did
        // not load is the case where silence costs the most.
        rejected.forEach { log.warn("Widget module {} was not loaded: {}", it.file.fileName, it.reason) }
    }
}
