package hivens.widget.loader

import hivens.widget.api.WidgetApi
import hivens.widget.api.WidgetRegistry
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
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
)

/**
 * Finds widget modules in a directory and loads the registries they carry.
 *
 * A module is a jar. There is no install step and no database: the file is there
 * or it is not, which is the model people already have for game mods and the one
 * thing about mod loading nobody needs explained.
 *
 * Each module gets its own [URLClassLoader] over the application's, which is
 * parent-first. That is deliberate and load-bearing: a widget is a composable,
 * and a composable compiled against a second copy of compose-runtime would hand
 * the wrong Composer type across every call. Delegating to the parent first
 * means a module that bundles its own Compose, kotlin-stdlib or widget-api
 * simply gets the launcher's, and only genuinely private dependencies come out
 * of the jar. One loader per module also keeps modules from seeing each other,
 * so a name collision between two of them is not a way to hijack a third.
 *
 * Nothing is sandboxed. A jar has whatever access the JVM has, and pretending
 * otherwise with a half-policy would be worse than saying so.
 */
class WidgetModuleLoader(
    private val directory: Path,
    private val parent: ClassLoader = WidgetModuleLoader::class.java.classLoader,
) {

    private val log = LoggerFactory.getLogger(WidgetModuleLoader::class.java)

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
        jars.forEach { jar ->
            when (val result = load(jar)) {
                is LoadedWidgetModule -> loaded += result
                is RejectedWidgetModule -> rejected += result
            }
        }

        report(loaded, rejected)
        return WidgetModuleScan(loaded, rejected)
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

        // From here the jar is held open by the loader. Every path that does not
        // hand it to a LoadedWidgetModule has to let it go again: a rejected jar
        // whose loader outlives the scan is a file the user cannot delete for the
        // rest of the session, and on Windows cannot replace either.
        val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), parent)
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

        return when (registries.size) {
            0 -> {
                loader.release()
                RejectedWidgetModule(jar, "declares the widget API but carries no registry service")
            }
            1 -> LoadedWidgetModule(id, name, jar, registries.single(), loader)
            // The processor emits exactly one per module. More than one means a
            // hand-assembled or merged jar, where which registry wins is not
            // something this can decide for the author.
            else -> {
                loader.release()
                RejectedWidgetModule(jar, "carries ${registries.size} registries; a module must carry one")
            }
        }
    }

    /** Closing a loader can throw; a jar we have already refused is not worth a failed scan. */
    private fun URLClassLoader.release() {
        runCatching { close() }.onFailure { log.debug("could not close a refused module's loader", it) }
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
