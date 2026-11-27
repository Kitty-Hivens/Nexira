package hivens.ui.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.widget.model.ConfigId
import hivens.history.api.History
import hivens.pages.api.HistoryEntry
import hivens.pages.api.Show
import kotlinx.serialization.json.JsonElement

/**
 * A composition's history as it is [now], for the owner that draws it. What each move does is [History]'s. [root] is
 * what it starts with, and what it starts over with when nothing in it exists any more.
 *
 * Moved on the thread that composes.
 */
@Stable
class CompositionHistory(private val root: ConfigId) {
    var now: History by mutableStateOf(History(root))
        private set

    fun show(show: Show) {
        now = now.show(show)
    }

    fun back() {
        now = now.back()
    }

    fun forward() {
        now = now.forward()
    }

    fun backTo(entry: HistoryEntry) {
        now = now.backTo(entry)
    }

    fun rewrite(values: Map<String, JsonElement?>) {
        now = now.rewrite(values)
    }

    fun retain(keep: (HistoryEntry) -> Boolean) {
        now = now.retain(root, keep)
    }
}
