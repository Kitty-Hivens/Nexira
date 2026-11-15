package hivens.widget.loader

import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ScreenEffect
import hivens.widget.api.WidgetApi
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.widgetPropsJson
import hivens.widget.model.ApiVersion
import hivens.widget.model.WidgetKind
import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.ServiceLoader
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
 *   `Nexira-Module-Requires`, by the packages [apiPackages] gives for that API id
 *   (`hivens.packs.api` for `nexira.packs`, `hivens.accounts.api` for
 *   `nexira.accounts`). An API the jar does not name is not visible to it, so what
 *   it compiles against is what the version check below has checked;
 * - Compose: `androidx` (Compose and the Jetpack libraries it is built on),
 *   `org.jetbrains.compose`, and Skiko, `org.jetbrains.skia` and `org.jetbrains.skiko`;
 * - the Kotlin standard library, `kotlin`, kotlinx coroutines and serialization, and slf4j;
 * - the media engine, `dev.hivens.skinema`, whose native libraries a process can
 *   load once and which the pixel player example plays through;
 * - the design system, nx-ui, by artifact and not by package: its classes are shared
 *   one by one from the list its build writes ([SHARED_ARTIFACTS], [SharedClassList]),
 *   because `hivens.ui.theme` and `hivens.ui.nx` also hold classes of the launcher's
 *   own interface, which stay hidden. With it, `com.materialkolor`, which its public
 *   API names. nx-ui is therefore part of what modules compile against, as the kernel
 *   is: a change that breaks its public API breaks the modules built on it.
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
 * seeing each other, so a name collision between two of them is not a way to hijack a
 * third.
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
 * [offered] is every module API this launcher carries, by API id, at the version it
 * carries. A jar that says in its manifest it was compiled against one of them is
 * refused when the API is not here, or is here in a version that does not serve the
 * one it was built against: another major, or an older minor than the jar's. An API
 * a jar names is offered by a module compiled into the application, so a jar that
 * only another jar could serve is refused too.
 *
 * [apiPackages] is the packages each offered API's artifact lives in, by API id,
 * which a jar that names the API is shown. A jar that names an offered API with no
 * packages here is refused, since it would load and fail on the first class it names.
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

        val loaded = mutableListOf<LoadedWidgetModule>()
        val rejected = mutableListOf<RejectedWidgetModule>()
        val off = mutableListOf<DisabledWidgetModule>()
        jars.forEach { jar ->
            when (val result = load(jar)) {
                is LoadedWidgetModule -> {
                    val first = loaded.firstOrNull { it.id == result.id }
                    if (first == null) {
                        loaded += result
                    } else {
                        // Settings, the on and off switch and the crash record are all kept by id, so
                        // two jars sharing one would be switched and set as a single module.
                        result.loader.release()
                        rejected += RejectedWidgetModule(jar, "its id '${result.id}' is already carried by ${first.file.fileName}")
                    }
                }
                is RejectedWidgetModule -> rejected += result
                is DisabledWidgetModule -> off += result
            }
        }

        report(loaded, rejected)
        off.forEach { log.info("Widget module '{}' ({}) is switched off and was not loaded", it.id, it.file.fileName) }
        return WidgetModuleScan(loaded, rejected, off)
    }

    private fun load(jar: Path): WidgetModuleResult {
        val manifest = runCatching {
            JarFile(jar.toFile()).use { it.manifest }
        }.getOrElse { return RejectedWidgetModule(jar, "not a readable jar: ${it.message}") }
            ?: return RejectedWidgetModule(jar, "no manifest")

        val attributes = manifest.mainAttributes
        val declared = attributes.getValue(WidgetApi.MANIFEST_VERSION)
            ?: return RejectedWidgetModule(jar, "no ${WidgetApi.MANIFEST_VERSION} in the manifest -- not a widget module")
        val version = declared.trim().toIntOrNull()
            ?: return RejectedWidgetModule(jar, "${WidgetApi.MANIFEST_VERSION} is '$declared', which is not a version number")
        if (version != WidgetApi.VERSION) {
            // Refusing is the feature. A module built against another ABI may
            // link and then misbehave, and a widget that draws the wrong thing
            // is harder to diagnose than one that never appears with a reason.
            return RejectedWidgetModule(
                jar,
                "built for widget API $version, this launcher speaks ${WidgetApi.VERSION}",
            )
        }

        val id = attributes.getValue(WidgetApi.MANIFEST_ID)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return RejectedWidgetModule(jar, "no ${WidgetApi.MANIFEST_ID} in the manifest")
        val name = attributes.getValue(WidgetApi.MANIFEST_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: id

        if (id in disabled) return DisabledWidgetModule(id, name, jar)

        val requiresText = attributes.getValue(WidgetApi.MANIFEST_REQUIRES)
        requirementFault(requiresText)?.let { return RejectedWidgetModule(jar, it) }
        val requires = requiresText?.let { ApiVersion.parseAll(it) }.orEmpty()
        unnamedApiFault(jar, requires.keys)?.let { return RejectedWidgetModule(jar, it) }

        val opened = runCatching { shadowCopy(jar) }.getOrElse {
            return RejectedWidgetModule(jar, "could not be copied aside to load: ${it.message}")
        }

        // From here the jar is held open by the loader. Every path that does not
        // hand it to a LoadedWidgetModule has to let it go again: a rejected jar
        // whose loader outlives the scan is a file the user cannot delete for the
        // rest of the session, and on Windows cannot replace either.
        //
        // Named after the module, which is what lets a crash be traced back to it:
        // every frame of a stack trace carries the name of the loader its class came
        // from. See [moduleIdIn].
        val view = ModuleClassView(VIEW_PREFIX + id, parent, visiblePackages(requires.keys), sharedClasses)
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
            contributionKinds.forEach { kind ->
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
                registries.isEmpty() && contributions.isEmpty() -> {
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
                    LoadedWidgetModule(id, name, jar, registry, loader, contributions, readStrings(opened, id))
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
                        val properties = Properties()
                        InputStreamReader(file.getInputStream(entry), Charsets.UTF_8).use { properties.load(it) }
                        val words = properties.stringPropertyNames().associateWith { properties.getProperty(it) }
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
     * Why a jar whose manifest names [declared] as the APIs it was built against cannot
     * run here, or null when every one is offered in a version that serves it.
     */
    private fun requirementFault(declared: String?): String? {
        if (declared == null) return null
        val required = ApiVersion.parseAll(declared)
            ?: return "${WidgetApi.MANIFEST_REQUIRES} is '$declared', which is not a list of id=major.minor"
        required.forEach { (api, builtAgainst) ->
            val here = offered[api] ?: return "built against the module API '$api' $builtAgainst, which this launcher does not carry"
            if (!here.serves(builtAgainst)) {
                return "built against the module API '$api' $builtAgainst, this launcher carries $here"
            }
            // An API the version check lets through has to be one the jar can see, or it
            // would load and then fail on the first class it names.
            if (apiPackages[api].isNullOrEmpty()) {
                return "built against the module API '$api', which this launcher carries without sharing its classes"
            }
        }
        return null
    }

    /**
     * Why a jar that carries services of a kind of contribution a module API declares, without
     * naming that API in [required], cannot work, or null when it names every such API. Read off
     * the jar's service files before any of its classes is loaded, so the reason says what to
     * change in the manifest rather than which class could not be linked. A jar that cannot be
     * listed is left to the reads that follow, which refuse it with their own reason.
     */
    private fun unnamedApiFault(jar: Path, required: Set<String>): String? {
        val owners = contributionKinds.mapNotNull { kind -> apiOwning(kind)?.let { kind to it } }
        if (owners.isEmpty()) return null
        val services = runCatching {
            JarFile(jar.toFile()).use { file ->
                file.entries().asSequence().map { it.name }.filter { it.startsWith(SERVICES_DIR) }.map { it.removePrefix(SERVICES_DIR) }.toSet()
            }
        }.getOrElse { return null }
        val (kind, api) = owners.firstOrNull { (kind, api) -> kind.name in services && api !in required } ?: return null
        return "carries ${kind.simpleName} services, which come from the module API '$api': name $api in ${WidgetApi.MANIFEST_REQUIRES}"
    }

    /** The module API whose packages hold [kind], or null for a kind of the kernel's own. */
    private fun apiOwning(kind: Class<*>): String? {
        val pkg = kind.packageName
        return apiPackages.entries.firstOrNull { (_, packages) -> packages.any { pkg == it || pkg.startsWith("$it.") } }?.key
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

    /** The packages a jar that names the APIs [required] is shown, see the class KDoc. */
    private fun visiblePackages(required: Set<String>): Set<String> =
        SHARED_PACKAGES + required.flatMap { apiPackages[it].orEmpty() }

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
