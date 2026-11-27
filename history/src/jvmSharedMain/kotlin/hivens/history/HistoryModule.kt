// The history module is compiled into the application and keeps its store for the host's levels, out of the reach
// of the modules the host does not trust.
@file:OptIn(HostApi::class)

package hivens.history

import hivens.history.api.History
import hivens.history.api.HistoryApi
import hivens.history.api.HistoryKeys
import hivens.pages.api.PagesApi
import hivens.pages.api.PagesKeys
import hivens.widget.api.CompiledModule
import hivens.widget.api.CompositionKeys
import hivens.widget.api.HostApi
import hivens.widget.api.InterfaceKeys
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelContext
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostService
import hivens.widget.api.hostServices
import hivens.widget.model.ApiVersion
import hivens.widget.model.ConfigId
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The history module as the application carries it: compiled in, and brought in by a document that names
 * [HistoryApi.ID] like any jar would be. Until the pages are a module of their own it answers the pages API's show
 * as well, so it offers both APIs.
 */
object HistoryModule : CompiledModule {
    override val id: String = HistoryApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(HistoryModule::class.java, HistoryApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(HistoryApi.ID to HistoryApi.VERSION, PagesApi.ID to PagesApi.VERSION)

    // Each API's own object lives in its artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(
        HistoryApi.ID to setOf(HistoryApi::class.java.name.substringBeforeLast('.')),
        PagesApi.ID to setOf(PagesApi::class.java.name.substringBeforeLast('.')),
    )
    override val contributions: List<ModuleContribution> = listOf(HistoryKeysContribution)

    private const val NAME = "history.name"
}

/**
 * Every key in [HistoryKeys] and [PagesKeys.Show], answered per composition from the history [Histories] keeps for it
 * by its name. A composition drawn without a name, a preview's, has a history of its own that goes with its level.
 */
object HistoryKeysContribution : KeyContribution {
    override val id: String = "history.keys"
    override val label: String = "History"

    override fun declare(keys: ModuleKeys) {
        keys.hostService(ScopeLevel.App) { Histories() }

        keys.source(HistoryKeys.History, sample = { History(SAMPLE_ROOT) }) { composition ->
            val history = composition.history() ?: return@source flowSource(MutableStateFlow(History(composition.start() ?: SAMPLE_ROOT)))
            // A config gone from the document leaves the history, back and forward both, so neither arrow opens a
            // page about nothing.
            composition.find(CompositionKeys.Configs)?.state?.let { configs ->
                composition.coroutineScope.launch { configs.collect { has -> history.retain { it.config in has } } }
            }
            flowSource(history.now)
        }
        keys.command(PagesKeys.Show) { composition ->
            command { show -> composition.history()?.show(show) { config -> composition.has(config) } }
        }
        keys.command(HistoryKeys.Back) { composition -> command { composition.history()?.back() } }
        keys.command(HistoryKeys.Forward) { composition -> command { composition.history()?.forward() } }
        keys.command(HistoryKeys.BackTo) { composition -> command { entry -> composition.history()?.backTo(entry) } }
        keys.command(HistoryKeys.Rewrite) { composition -> command { values -> composition.history()?.rewrite(values) } }
    }

    /** What the palette shows a history as, and where a composition with no start of its own starts. */
    private val SAMPLE_ROOT = ConfigId("home")
}

/** The history of the composition this level is, or null for one drawn without its name or start. */
private fun LevelContext.history(): CompositionHistory? {
    val name = find(CompositionKeys.Name)?.state?.value ?: return null
    val root = start() ?: return null
    return hostServices.get<Histories>().of(name, root, find(InterfaceKeys.Restarts)?.state?.value)
}

private fun LevelContext.start(): ConfigId? = find(CompositionKeys.Start)?.state?.value

/** Whether the composition has [config], or true where nothing says which configs it has. */
private fun LevelContext.has(config: ConfigId): Boolean = find(CompositionKeys.Configs)?.state?.value?.contains(config) ?: true
