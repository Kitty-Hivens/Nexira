// The manager is compiled into the application and trusted with the host's own services: the axes,
// providers and packs the modules carry reach it as the host hands them over, through the host view of
// the app level, and before the boot through the services the host hands every module then.
@file:OptIn(HostApi::class)

package hivens.look.manager

import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookAxis
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookKeys
import hivens.look.manager.api.LookPack
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookProvision
import hivens.look.manager.api.LookSnapshot
import hivens.look.manager.api.LookState
import hivens.look.manager.api.LookView
import hivens.widget.api.BootAnswers
import hivens.widget.api.BootContext
import hivens.widget.api.BootValues
import hivens.widget.api.BringIn
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.ServiceContext
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.service
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The look manager as the application carries it: compiled in, and brought in by a document that names
 * [LookApi.ID]. A document names the manager and no axis, provider or pack: those are data the manager
 * takes from every module compiled in, brought in or not, and from every jar switched on, and of them it
 * opens only the providers the person's choice needs. Once the host is up it brings in the compiled
 * module of each provider it opens, so a document that names only the manager still has the keys,
 * services and settings of the modules whose look is drawn.
 *
 * It answers [LookKeys] at the app level, over its settings domain `look`, and the same keys before the
 * boot, worked out from the same file and the same modules, so the window the launcher boots in opens in
 * the look the shell then draws. Beside them it declares [LookView], an app service every module reads,
 * for services, which read no source.
 */
object LookManagerModule : CompiledModule {
    override val id: String = LookApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(LookManagerModule::class.java, LookApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(LookApi.ID to LookApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(LookApi.ID to setOf(LookApi::class.java.name.substringBeforeLast('.')))
    override val contributionKinds: List<Class<out ModuleContribution>> =
        listOf(LookAxis::class.java, LookProvider::class.java, LookPack::class.java)
    override val contributions: List<ModuleContribution> = listOf(LookKeysContribution)
    override val boot: BootAnswers = BootAnswers(::bootAnswer)

    private const val NAME = "look.name"
}

/** What the person chose: the pack and any axis they want otherwise, in `look.json` under the settings folder. */
object LookSettingsDomain : SettingsDomain<LookChoice>("look", LookChoice.serializer()) {
    override fun defaults(): LookChoice = LookChoice.DEFAULT
}

/** The keys in [LookKeys], answered over the module's own manager. */
object LookKeysContribution : KeyContribution {
    override val id: String = "look.keys"
    override val label: String = "Look"

    override fun declare(keys: ModuleKeys) {
        keys.settings(LookSettingsDomain, LookKeys.Settings, LookKeys.UpdateSettings)
        keys.service(ScopeLevel.App, ::moduleManager)
        keys.service(ScopeLevel.App, LookView::class) { context -> context.services.get<LookManager>().view }
        keys.source(LookKeys.Provide, sample = { LookProvision.NONE }) { app -> flowSource(app.services.get<LookManager>().provision) }
        keys.source(LookKeys.State, sample = { LookState.EMPTY }) { app -> flowSource(app.services.get<LookView>().state) }
        keys.source(LookKeys.Answers, sample = { LookSnapshot.EMPTY }) { app -> flowSource(app.services.get<LookView>().answers) }
    }
}

/**
 * The manager for the app, over its settings domain and what the modules brought in carry of the look.
 * A provider is opened with the services its own module sees, the way the catalogue opens a source, and
 * a provider of a compiled module the document does not name has its module brought in first, so its
 * keys, services and settings are there. One whose module the host does not bring in is passed over.
 */
private fun moduleManager(context: ServiceContext): LookManager {
    val taken = context.hostServices.find<TakenContributions>()
    val compiled = taken?.compiledIn.orEmpty().mapTo(HashSet()) { it.moduleId }
    val bringIn = context.hostServices.find<BringIn>()
    return LookManager(
        choice     = context.services.get<SettingsStores>().of(LookSettingsDomain).value,
        taken      = taken?.let { lookTaken(it, context.coroutineScope) } ?: MutableStateFlow(emptyList()),
        servicesOf = { moduleId ->
            check(moduleId !in compiled || bringIn?.bringIn(moduleId) != false) { "the host did not bring in module '$moduleId'" }
            context.servicesOf(moduleId)
        },
        scope      = context.coroutineScope,
        compiled   = compiled,
    )
}

/**
 * What [taken] carries that the manager reads, as it changes: what every module compiled in carries,
 * brought in or not, then what the jars switched on carry, in the order they load.
 *
 * A new list only where a contribution came or went. The host hands its list over anew when a compiled
 * module is brought in, which the manager does in the middle of a pass, and a list that changed only
 * there would start another pass for nothing.
 */
internal fun lookTaken(taken: TakenContributions, scope: CoroutineScope): StateFlow<List<TakenContribution<ModuleContribution>>> {
    val compiled = taken.compiledIn
    val compiledIds = compiled.mapTo(HashSet()) { it.moduleId }
    fun of(all: List<TakenContribution<ModuleContribution>>) = compiled + all.filter { it.moduleId !in compiledIds }
    val out = MutableStateFlow(of(taken.all.value))
    scope.launch {
        taken.all.collect { now ->
            val next = of(now)
            if (!sameContributions(out.value, next)) out.value = next
        }
    }
    return out.asStateFlow()
}

/** Whether [a] and [b] hold the same contributions of the same modules in the same order. */
private fun sameContributions(a: List<TakenContribution<ModuleContribution>>, b: List<TakenContribution<ModuleContribution>>): Boolean =
    a.size == b.size && a.indices.all { a[it].moduleId == b[it].moduleId && a[it].item === b[it].item }

/**
 * The look the boot window is drawn in: the manager's answers over the same choice and the same modules
 * the shell then opens it on, worked out once and let go of, so the providers it opened are closed again
 * before the boot window draws. The boot asks off the drawing thread, so the pass runs on the thread
 * that asks and the providers' own work on a background one. A jar is not loaded yet, so a choice that
 * names a jar's pack or family opens the boot window on what comes next in the choice, quietly, as the
 * shell does while the jar is away. A provider is opened with the services the host hands every module
 * then and its own module's boot context, so it reads its own files as it does once the host is up.
 */
private fun bootAnswer(context: BootContext): BootValues {
    val choice = context.settings(LookSettingsDomain)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
        val host = context.services.find<TakenContributions>()
        val manager = LookManager(
            choice             = MutableStateFlow(choice),
            taken              = host?.let { lookTaken(it, scope) } ?: MutableStateFlow(emptyList()),
            servicesOf         = { context.services },
            scope              = scope,
            compiled           = host?.compiledIn.orEmpty().mapTo(HashSet()) { it.moduleId },
            worker             = Dispatchers.Unconfined,
            providerDispatcher = Dispatchers.Default,
            bootOf             = context::ofModule,
        )
        try {
            val look = manager.detached()
            return BootValues.of {
                put(LookKeys.Settings, choice)
                put(LookKeys.State, look.state)
                put(LookKeys.Provide, look.provision)
                put(LookKeys.Answers, look.snapshot)
            }
        } finally {
            manager.close()
        }
    } finally {
        scope.cancel()
    }
}
