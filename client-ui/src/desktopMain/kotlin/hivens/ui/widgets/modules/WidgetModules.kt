package hivens.ui.widgets.modules

import hivens.core.io.AtomicFiles
import hivens.widget.api.CompositeWidgetRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.loader.LoadedWidgetModule
import hivens.widget.loader.WidgetModuleLoader
import hivens.widget.loader.WidgetModuleScan
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The widget modules this launcher has, and the registry they make with the
 * built-in widgets, changeable while the launcher runs.
 *
 * A module is a jar in the widgets folder. It can be switched off and on, the
 * folder can be read again to pick up a jar added, replaced or removed, and none
 * of it needs a restart: [state] carries a new registry, the composition reads it,
 * the widgets of a module that left are drawn as the unknown kind they now are,
 * with everything they carried kept in the layout, and they come back when the
 * module does.
 *
 * It is also the launcher's protection against a module that breaks. A failure
 * whose stack runs through a module's code is traced to that module by the name
 * its class loader carries, and the shell's recovery switches the module off
 * before it starts again, instead of retrying the same crash until safe mode.
 *
 * Every module ever loaded is remembered with the widget kinds it brought, until
 * somebody forgets it. That is what keeps a module that is off, broken or gone
 * from losing its widgets to the schema-bump prune, which otherwise reads "not in
 * the registry" as "renamed away" and deletes them for good.
 */
class WidgetModules(
    private val directory: Path,
    private val shadowDir: Path,
    private val stateFile: Path,
    private val json: Json,
    private val builtIn: WidgetRegistry,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger("Widgets")
    private val lock = Any()

    /** What is switched off and why, and every module's kinds, as last written. */
    @Serializable
    private data class Saved(
        /** Module id to why it is off: blank when somebody switched it off, the failure when it crashed. */
        val disabled: Map<String, String> = emptyMap(),
        /** Module id to the widget kinds it brought the last time it loaded. */
        val known: Map<String, List<String>> = emptyMap(),
    )

    /** One line of the module list. */
    sealed interface Entry {
        /** Loaded and in the registry. */
        data class Loaded(val id: String, val name: String, val file: Path, val kinds: Int) : Entry

        /** Switched off. [crash] is the failure that switched it off, null when a person did. */
        data class Off(val id: String, val name: String, val file: Path, val crash: String?) : Entry

        /** A jar in the folder that is not a module this launcher can load, and why. */
        data class Refused(val file: Path, val reason: String) : Entry

        /** Remembered, with no jar in the folder any more. Its widgets are kept until it is forgotten. */
        data class Gone(val id: String) : Entry
    }

    /** The registry and the list, together, so a reader never sees one without the other. */
    data class Snapshot(val registry: WidgetRegistry, val entries: List<Entry>)

    /** A module that crashed and was switched off, for the notice the shell shows once it is back up. */
    data class CrashNotice(val name: String, val failure: String)

    private var saved: Saved = readSaved()
    private var live: List<LoadedWidgetModule> = emptyList()
    private val _state = MutableStateFlow(Snapshot(builtIn, emptyList()))
    private val _crashNotice = MutableStateFlow<CrashNotice?>(null)

    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Set when a module was switched off after crashing, until the shell has said so. */
    val crashNotice: StateFlow<CrashNotice?> = _crashNotice.asStateFlow()

    /**
     * The registry for readers outside the composition, which always answers with
     * whatever is current. The composition reads [state] instead, so it hears the
     * change.
     */
    val registry: WidgetRegistry = object : WidgetRegistry {
        override fun all(): Map<WidgetKind, WidgetDescriptor> = _state.value.registry.all()
        override fun get(kind: WidgetKind): WidgetDescriptor? = _state.value.registry[kind]
    }

    init {
        reload()
    }

    /**
     * Reads the folder again. A jar added since is loaded, a replaced one is loaded
     * afresh, one taken away leaves, and what is switched off stays closed.
     *
     * The loaders of modules that left are closed after a grace period rather than
     * at once: the composition is still drawing their widgets until it has read the
     * new registry, and code it has not loaded yet would have nowhere to come from.
     */
    fun reload() {
        synchronized(lock) {
            val scan = WidgetModuleLoader(directory, shadowDir = shadowDir, disabled = saved.disabled.keys).scan()
            val known = saved.known + scan.loaded.associate { m -> m.id to m.registry.all().keys.map { it.value }.sorted() }
            if (known != saved.known) {
                saved = saved.copy(known = known)
                write()
            }
            val leaving = live.filter { old -> scan.loaded.none { it.loader === old.loader } }
            live = scan.loaded
            _state.value = snapshotOf(scan)
            release(leaving)
        }
    }

    /** Switches a module on or off, and reads the folder again so it takes effect. */
    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            saved = saved.copy(disabled = if (enabled) saved.disabled - id else saved.disabled + (id to ""))
            write()
        }
        reload()
    }

    /**
     * Forgets a module whose jar is gone, and with it the record that kept its
     * widgets from the prune. The widgets themselves stay in the layout until the
     * next schema bump reaps them or somebody removes them.
     */
    fun forget(id: String) {
        synchronized(lock) {
            saved = saved.copy(known = saved.known - id, disabled = saved.disabled - id)
            write()
        }
        reload()
    }

    /** The folder the modules are read from. */
    val folder: Path get() = directory

    /** Every widget kind a remembered module brings, loaded or not. */
    fun knownKinds(): Set<WidgetKind> =
        synchronized(lock) { saved.known.values.flatten().mapTo(HashSet()) { WidgetKind(it) } }

    /** The loaded module whose code is on [failure]'s stack, or null when none is. */
    fun culpritOf(failure: Throwable): String? {
        val id = WidgetModuleLoader.moduleIdIn(failure) ?: return null
        return id.takeIf { live.any { it.id == id } }
    }

    /**
     * Switches off the module [id] after it crashed the shell, remembering the
     * failure, and leaves a notice for the shell to show once it is back up.
     */
    fun switchOffAfterCrash(id: String, failure: Throwable) {
        val name = live.firstOrNull { it.id == id }?.name ?: id
        val summary = (failure.message ?: failure.javaClass.simpleName).take(MAX_FAILURE_LENGTH)
        synchronized(lock) {
            saved = saved.copy(disabled = saved.disabled + (id to summary))
            write()
        }
        log.warn("Widget module '{}' crashed the interface and was switched off: {}", id, summary)
        _crashNotice.value = CrashNotice(name, summary)
        reload()
    }

    /** The shell has shown the crash notice. */
    fun consumeCrashNotice() {
        _crashNotice.value = null
    }

    private fun snapshotOf(scan: WidgetModuleScan): Snapshot {
        val sources = listOf(builtIn) + scan.loaded.map { it.registry }
        val labels = listOf("built-in") + scan.loaded.map { it.id }
        val composite = CompositeWidgetRegistry(sources)
        log.info("Widget registry: {} kinds from {} source(s) [{}]", composite.all().size, sources.size, labels.joinToString(", "))
        // A contribution that loses its id loses it silently otherwise: the widget
        // simply never appears. The composite has no logger of its own.
        composite.shadowed.forEach {
            log.warn("Widget '{}' from '{}' is shadowed by '{}' and will not be used", it.kind.value, labels[it.bySource], labels[it.heldBy])
        }
        val present = (scan.loaded.map { it.id } + scan.disabled.map { it.id }).toSet()
        val entries = buildList {
            scan.loaded.forEach { add(Entry.Loaded(it.id, it.name, it.file, it.registry.all().size)) }
            scan.disabled.forEach { add(Entry.Off(it.id, it.name, it.file, saved.disabled[it.id]?.takeIf { s -> s.isNotBlank() })) }
            scan.rejected.forEach { add(Entry.Refused(it.file, it.reason)) }
            (saved.known.keys + saved.disabled.keys).filter { it !in present }.sorted().forEach { add(Entry.Gone(it)) }
        }
        return Snapshot(composite, entries)
    }

    private fun release(leaving: List<LoadedWidgetModule>) {
        if (leaving.isEmpty()) return
        scope.launch {
            delay(RELEASE_GRACE)
            leaving.forEach { module ->
                runCatching { module.loader.close() }
                    .onFailure { log.debug("could not close the loader of widget module '{}'", module.id, it) }
            }
            sweepShadows()
        }
    }

    /** Copies no live loader reads from any more. Best effort: a host that will not delete an open file keeps it for later. */
    private fun sweepShadows() {
        val inUse = synchronized(lock) { live.flatMap { m -> m.loader.urLs.map { Path.of(it.toURI()).fileName.toString() } }.toSet() }
        runCatching {
            Files.list(shadowDir).use { files ->
                files.filter { it.fileName.toString() !in inUse }.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun readSaved(): Saved =
        runCatching { json.decodeFromString(Saved.serializer(), Files.readString(stateFile)) }
            .getOrElse { Saved() }

    private fun write() {
        runCatching { AtomicFiles.writeString(stateFile, json.encodeToString(Saved.serializer(), saved)) }
            .onFailure { log.warn("Could not save the widget module list to {}", stateFile, it) }
    }

    private companion object {
        /** Long enough for the composition to have drawn a frame without the module. */
        val RELEASE_GRACE = 3.seconds

        /** A failure is shown in the module list and a notice, so it is kept to a line. */
        const val MAX_FAILURE_LENGTH = 300
    }
}
