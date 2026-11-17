package hivens.ui

import hivens.config.Storage
import hivens.ui.host.LauncherFiles
import hivens.ui.host.LauncherHostAccess
import hivens.ui.layout.LayoutReconcile
import hivens.ui.settings.SettingsDomainFiles
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.modules.hostTakenContributions
import hivens.ui.widgets.modules.hostTakenWords
import hivens.widget.api.BringIn
import hivens.widget.api.CompiledModule
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostFiles
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenContributions
import hivens.widget.api.TakenWords
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.current
import hivens.widget.model.CommandKey
import hivens.widget.model.DefaultLayout
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.reflect.KClass
import kotlin.test.assertNotNull

/**
 * The app level the launcher makes over [dataDir], with the keys of the modules in [compiled] that
 * [named] names and the jar modules [jars] carries: the host services the modules compiled in are
 * trusted with, made the way the launcher's Koin makes them, and nothing else of the launcher.
 *
 * For the tests that read what a module answers once its keys are up, beside what it answered
 * before the boot from the same files.
 */
internal class ModuleAppLevel(
    dataDir: Path,
    compiled: List<CompiledModule>,
    scope: CoroutineScope,
    val jars: MutableStateFlow<WidgetModules.Snapshot> = MutableStateFlow(noJars()),
    named: Set<String> = compiled.map { it.id }.toSet(),
    files: HostFiles = LauncherFiles(dataDir),
    words: TakenWords? = null,
) : AutoCloseable {

    /** The modules' settings, under [dataDir], read and written directly. */
    val settings = SettingsDomainFiles(dataDir.resolve(Storage.SETTINGS_DIR), dataDir.resolve(Storage.SETTINGS_FILE))

    private val keys = HostKeys(WidgetDataRegistry(), WidgetCommandRegistry(), ContributedServices()).also { keys ->
        moduleKeys(keys, named, compiled, jars = emptyList())
    }

    /** The app's source registry, which the host provides at its composition root. */
    val registry: WidgetDataRegistry get() = keys.sources

    /** The app's command registry, which the host provides at its composition root. */
    val commands: WidgetCommandRegistry get() = keys.commands

    /** The compiled modules in: the ones [named] names, and the ones brought in since. */
    val modulesIn = ModulesIn(named)

    /** How a trusted module brings in one of [compiled] that [named] leaves out, as the launcher's Koin makes it. */
    val bringIn = HostBringIn(keys, compiled, modulesIn, app = { app })

    private val host = FixedHost(
        mapOf(
            HostFiles::class to files,
            SettingsStores::class to settings,
            TakenWords::class to (words ?: hostTakenWords(compiled, jars, scope)),
            TakenContributions::class to hostTakenContributions(modulesIn.ids, compiled, jars, scope),
            BringIn::class to bringIn,
        ),
    )

    val app: WidgetScope = WidgetScope.app(
        registry = keys.sources,
        services = keys.appServices(host, scope.coroutineContext),
        context  = scope.coroutineContext,
        commands = keys.commands,
        access   = LauncherHostAccess,
    )

    /** What the app level answers for [key] now, failing where nothing does. */
    fun <T> value(key: SourceKey<T>): T = assertNotNull(app.current(key), "${key.id} has no answer at the app level")

    /** Fires [key] at the app level with [payload], failing where nothing answers it. */
    fun <P> fire(key: CommandKey<P>, payload: P) = assertNotNull(app.findCommand(key), "${key.id} has no answer at the app level").run(payload)

    override fun close() {
        app.close()
        keys.close()
    }

    /** The host's own services, which are [byType] and nothing else, at every level. */
    private class FixedHost(private val byType: Map<KClass<*>, Any>) : LevelServicesHost {
        override val services: LevelServices = object : LevelServices {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> find(type: KClass<T>): T? = byType[type] as T?

            override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException("no ${type.qualifiedName} here")
        }

        override fun child(level: ScopeLevel): LevelServicesHost = this

        override fun close() = Unit
    }

    companion object {
        /** A module list with no jar loaded. */
        fun noJars(): WidgetModules.Snapshot = WidgetModules.Snapshot(NoWidgets, emptyList())

        /** Writes the person's document under [dataDir]: the bundled arrangement, naming [modules]. */
        fun writeDocument(dataDir: Path, modules: List<String>) {
            val graph = Json.encodeToJsonElement(LayoutGraph.serializer(), DefaultLayout.load().copy(modules = modules))
            val envelope = JsonObject(mapOf("schema_version" to JsonPrimitive(LayoutReconcile.CURRENT_SCHEMA), "graph" to graph))
            dataDir.resolve(Storage.LAYOUT_GRAPH_FILE).writeText(envelope.toString())
        }
    }

    private object NoWidgets : WidgetRegistry {
        override fun all(): Map<WidgetKind, WidgetDescriptor> = emptyMap()
        override fun get(kind: WidgetKind): WidgetDescriptor? = null
    }
}
