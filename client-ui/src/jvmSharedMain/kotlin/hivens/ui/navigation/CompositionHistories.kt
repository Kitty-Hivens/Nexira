package hivens.ui.navigation

import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId

/**
 * The histories of the compositions shown in this run, one per composition, kept apart from what draws them: an
 * interface that starts over finds each where it stood. Nothing is kept past the launcher's quitting.
 */
class CompositionHistories {
    private val kept = HashMap<CompositionName, CompositionHistory>()

    /** The history of [composition], starting at [root] where it has none yet. */
    @Synchronized
    fun of(composition: CompositionName, root: ConfigId): CompositionHistory = kept.getOrPut(composition) { CompositionHistory(root) }

    /**
     * Moves every history off what it showed when the interface crashed: what was shown may be what it crashed on,
     * and shown again by itself it would crash every restart until the crash guard gave up on the interface. The
     * first crash steps back one entry. One [again], before the interface came up and stayed up since the last, goes
     * back to the root, since the page behind was no better, so no crash restarts further back than going home did.
     * What was left stays ahead, and a history at its root stays there.
     */
    @Synchronized
    fun afterCrash(again: Boolean) {
        kept.values.filter { it.now.canGoBack }.forEach { history ->
            if (again) history.backTo(history.now.entries.first()) else history.back()
        }
    }
}
