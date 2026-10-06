package hivens.widget.loader

import hivens.widget.api.WidgetApi
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.widgetPropsJson
import org.slf4j.LoggerFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
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
 */
class WidgetModuleLoader(
    private val directory: Path,
    private val parent: ClassLoader = WidgetModuleLoader::class.java.classLoader,
    private val shadowDir: Path? = null,
    private val disabled: Set<String> = emptySet(),
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
        val off = mutableListOf<DisabledWidgetModule>()
        jars.forEach { jar ->
            when (val result = load(jar)) {
                is LoadedWidgetModule -> loaded += result
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
        val loader = URLClassLoader(LOADER_PREFIX + id, arrayOf(opened.toUri().toURL()), parent)
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
            1 -> {
                // Listed once here, where a failure is this module's alone. Everything
                // downstream reads the list outside any guard, building the registry
                // the whole shell resolves from, so a registry that throws there took
                // the shell down on every start instead of being refused by name.
                val registry = registries.single()
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
                LoadedWidgetModule(id, name, jar, registry, loader)
            }
            // The processor emits exactly one per module. More than one means a
            // hand-assembled or merged jar, where which registry wins is not
            // something this can decide for the author.
            else -> {
                loader.release()
                RejectedWidgetModule(jar, "carries ${registries.size} registries; a module must carry one")
            }
        }
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
