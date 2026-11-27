package hivens.history

import hivens.history.api.History
import hivens.pages.api.HistoryEntry
import hivens.pages.api.Show
import hivens.widget.api.InterfaceRestarts
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * A composition's history as it is [now]. What each move does is [History]'s. [root] is what it starts with, and what
 * it starts over with when nothing in it exists any more. Moved from any thread.
 */
class CompositionHistory internal constructor(private val root: ConfigId) {
    private val state = MutableStateFlow(History(root))

    val now: StateFlow<History> = state.asStateFlow()

    /** Shows what [show] asks for, where [has] says the composition has its config. */
    fun show(show: Show, has: (ConfigId) -> Boolean = { true }) {
        if (has(show.config)) state.update { it.show(show) }
    }

    fun back() = state.update { it.back() }

    fun forward() = state.update { it.forward() }

    fun backTo(entry: HistoryEntry) = state.update { it.backTo(entry) }

    fun rewrite(values: Map<String, JsonElement?>) = state.update { it.rewrite(values) }

    /** Drops every entry [keep] refuses, see [History.retain]. */
    fun retain(keep: (HistoryEntry) -> Boolean) = state.update { it.retain(root, keep) }
}

/**
 * The histories of the compositions shown this run, one per composition by its name, kept for the app: an interface
 * that starts over finds each where it stood. Nothing is kept past the launcher's quitting.
 */
class Histories {
    private val kept = HashMap<CompositionName, CompositionHistory>()

    // The interface's starts over after a crash already stepped back from.
    private var restartsSeen = 0

    /**
     * The history of [composition], starting at [root] where it has none yet. Each start over after a crash in
     * [restarts] not yet seen moves every history off what it showed first, see [afterCrash].
     */
    @Synchronized
    fun of(composition: CompositionName, root: ConfigId, restarts: InterfaceRestarts? = null): CompositionHistory {
        if (restarts != null && restarts.count > restartsSeen) {
            restartsSeen = restarts.count
            afterCrash(restarts.again)
        }
        return kept.getOrPut(composition) { CompositionHistory(root) }
    }

    /**
     * Moves every history off what it showed when the interface crashed: what was shown may be what it crashed on,
     * and shown again by itself it would crash every restart until the crash guard gave up on the interface. The
     * first crash steps back one entry. One [again], before the interface came up and stayed up since the last, goes
     * back to the root, since the page behind was no better, so no crash restarts further back than going home did.
     * What was left stays ahead, and a history at its root stays there.
     */
    @Synchronized
    fun afterCrash(again: Boolean) {
        kept.values.filter { it.now.value.canGoBack }.forEach { history ->
            if (again) history.backTo(history.now.value.entries.first()) else history.back()
        }
    }
}
