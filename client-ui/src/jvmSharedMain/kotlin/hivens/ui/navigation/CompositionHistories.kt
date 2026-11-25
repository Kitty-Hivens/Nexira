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
     * Steps every history back one entry, after the interface crashed: what was shown may be what it crashed on, and
     * shown again by itself it would crash every restart until none is left. It stays one step forward, and a history
     * at its root stays there.
     */
    @Synchronized
    fun stepBackAfterCrash() {
        kept.values.filter { it.now.canGoBack }.forEach { it.back() }
    }
}
