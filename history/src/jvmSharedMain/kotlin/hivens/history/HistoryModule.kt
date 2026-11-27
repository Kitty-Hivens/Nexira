// The history module is compiled into the application and keeps its store for the host's levels, out of the reach
// of the modules the host does not trust.
@file:OptIn(HostApi::class)

package hivens.history

import hivens.history.api.History
import hivens.history.api.HistoryApi
import hivens.history.api.HistoryKeys
import hivens.pages.api.HistoryEntry
import hivens.pages.api.PagesApi
import hivens.pages.api.presentKey
import hivens.pages.api.PagesKeys
import hivens.pages.api.ScreenId
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
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * The history module as the application carries it: compiled in, and brought in by a document that names
 * [HistoryApi.ID] like any jar would be. It keeps what the pages module's screens show, so it requires that module,
 * and answers what is shown and the pages' show.
 */
object HistoryModule : CompiledModule {
    override val id: String = HistoryApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(HistoryModule::class.java, HistoryApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(HistoryApi.ID to HistoryApi.VERSION)
    override val requires: Map<String, ApiVersion> = mapOf(PagesApi.ID to PagesApi.VERSION)

    // The API's own object lives in its artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(HistoryApi.ID to setOf(HistoryApi::class.java.name.substringBeforeLast('.')))
    override val contributions: List<ModuleContribution> = listOf(HistoryKeysContribution)

    private const val NAME = "history.name"
}

/**
 * Every key in [HistoryKeys] and the pages API's [PagesKeys.Show], [PagesKeys.Shown] and [PagesKeys.Kept], answered
 * per composition from the history [Histories] keeps for it by its name, starting on the screen the pages module says
 * the composition starts on. A composition drawn without a name, a preview's, has a history of its own that goes with
 * its level.
 */
object HistoryKeysContribution : KeyContribution {
    override val id: String = "history.keys"
    override val label: String = "History"

    override fun declare(keys: ModuleKeys) {
        keys.hostService(ScopeLevel.App) { Histories() }

        keys.source(HistoryKeys.History, sample = { History(SAMPLE_ROOT) }) { composition ->
            val history = composition.history() ?: return@source flowSource(MutableStateFlow(History(composition.start() ?: SAMPLE_ROOT)))
            // A screen gone leaves the history, back and forward both, so neither arrow opens a page about nothing.
            composition.find(PagesKeys.Screens)?.state?.let { screens ->
                composition.coroutineScope.launch {
                    screens.collect { all -> if (all.isNotEmpty()) history.retain { entry -> all.any { it.id == entry.screen } } }
                }
            }
            // So does a page opened with a value its module says is gone, a page of a pack deleted say.
            composition.coroutineScope.launch { composition.presentValues(history).collect { present -> history.retain { it.stillThere(present) } } }
            flowSource(history.now)
        }
        keys.source(PagesKeys.Shown, sample = { null }) { composition ->
            val history = composition.history() ?: return@source flowSource(MutableStateFlow(null))
            flowSource(history.now.map { it.current }.stateIn(composition.coroutineScope, SharingStarted.Eagerly, history.now.value.current))
        }
        keys.source(PagesKeys.Kept, sample = { emptySet() }) { composition ->
            val history = composition.history() ?: return@source flowSource(MutableStateFlow(emptySet()))
            fun kept(now: History) = now.reachable.mapTo(HashSet()) { it.page }
            flowSource(history.now.map(::kept).stateIn(composition.coroutineScope, SharingStarted.Eagerly, kept(history.now.value)))
        }
        keys.command(PagesKeys.Show) { composition ->
            command { show -> composition.history()?.show(show) { screen -> composition.has(screen) } }
        }
        keys.command(HistoryKeys.Back) { composition -> command { composition.history()?.back() } }
        keys.command(HistoryKeys.Forward) { composition -> command { composition.history()?.forward() } }
        keys.command(HistoryKeys.BackTo) { composition -> command { entry -> composition.history()?.backTo(entry) } }
        keys.command(HistoryKeys.Rewrite) { composition -> command { values -> composition.history()?.rewrite(values) } }
    }

    /** What the palette shows a history as, and where a composition with no start of its own starts. */
    private val SAMPLE_ROOT = ScreenId("home")
}

/** The history of the composition this level is, or null for one drawn without its name or start. */
private fun LevelContext.history(): CompositionHistory? {
    val name = find(CompositionKeys.Name)?.state?.value ?: return null
    val root = start() ?: return null
    return hostServices.get<Histories>().of(name, root, find(InterfaceKeys.Restarts)?.state?.value)
}

/** The screen the composition starts on, as the pages module says. */
private fun LevelContext.start(): ScreenId? = find(PagesKeys.Start)?.state?.value

/** Whether the composition has [screen], or true where nothing says which screens it has. */
private fun LevelContext.has(screen: ScreenId): Boolean =
    find(PagesKeys.Screens)?.state?.value?.takeIf { it.isNotEmpty() }?.any { it.id == screen } ?: true

/**
 * What still exists of every value the entries of [history] were opened with, by the value's id, for each value whose
 * module answers [presentKey]. Follows the entries as they change, and the answers as they do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun LevelContext.presentValues(history: CompositionHistory): Flow<Map<String, Set<JsonElement>>> =
    history.now
        .map { now -> now.reachable.flatMapTo(HashSet()) { it.opened.keys } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            val answered = ids.mapNotNull { id -> find(presentKey(id))?.state?.map { id to it } }
            if (answered.isEmpty()) flowOf(emptyMap()) else combine(answered) { it.toMap() }
        }

/** Whether every value this entry was opened with that [present] answers for is among them. */
private fun HistoryEntry.stillThere(present: Map<String, Set<JsonElement>>): Boolean =
    opened.all { (id, value) -> present[id]?.contains(value) ?: true }
