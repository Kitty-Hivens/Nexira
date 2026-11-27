package hivens.history.api

import hivens.pages.api.HistoryEntry
import hivens.pages.api.Show
import hivens.widget.model.ConfigId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Where a composition has been, where it is, and where forward leads. A value: a move answers a new one.
 *
 * [entries] run from the root to the one shown and are never empty. [ahead] are the entries back stepped off, the
 * next one forward last. Moving somewhere new drops them.
 *
 * Back never leaves the root, and what it means there is the composition's own business. [backsAtRoot] counts how
 * often it was asked for with nowhere to go, for a composition that answers with a question, say.
 */
@Serializable
data class History(
    val entries: List<HistoryEntry>,
    val ahead: List<HistoryEntry> = emptyList(),
    val backsAtRoot: Int = 0,
) {
    init {
        require(entries.isNotEmpty()) { "a history shows something" }
    }

    constructor(root: ConfigId) : this(listOf(HistoryEntry(root)))

    /** The entry shown. */
    val current: HistoryEntry get() = entries.last()

    /** Every entry back or forward can still reach. An entry not among them has left the history for good. */
    val reachable: List<HistoryEntry> get() = entries + ahead

    val canGoBack: Boolean get() = entries.size > 1

    val canGoForward: Boolean get() = ahead.isNotEmpty()

    /** Shows what [show] asks for. Asked for the page shown already, with no [Show.over], nothing moves. */
    fun show(show: Show): History {
        val target = HistoryEntry(show.config, show.values)
        val under = show.over?.let(::cutTo) ?: entries
        if (under.last().page != target.page) return copy(entries = under + target, ahead = emptyList())
        return if (show.over == null) this else copy(entries = under, ahead = emptyList())
    }

    private fun cutTo(config: ConfigId): List<HistoryEntry> {
        val root = HistoryEntry(config)
        return entries.take(entries.indexOfFirst { it.page == root.page } + 1).ifEmpty { listOf(root) }
    }

    /** Steps back one entry. At the root nothing moves and the asking is counted in [backsAtRoot]. */
    fun back(): History =
        if (canGoBack) copy(entries = entries.dropLast(1), ahead = ahead + current) else copy(backsAtRoot = backsAtRoot + 1)

    /** Steps forward to the entry back last stepped off, or stays. */
    fun forward(): History = if (canGoForward) copy(entries = entries + ahead.last(), ahead = ahead.dropLast(1)) else this

    /** Steps back to the nearest entry of [entry]'s page, or stays where that is the one shown or is not behind it. */
    fun backTo(entry: HistoryEntry): History {
        val at = entries.indexOfLast { it.page == entry.page }
        if (at < 0 || at == entries.lastIndex) return this
        return copy(entries = entries.take(at + 1), ahead = ahead + entries.drop(at + 1).asReversed())
    }

    /**
     * Rewrites values of the entry shown, for a page noting what it wants to find when back returns to it: each id in
     * [values] gets its value, and one with null is taken out. The page stays the one it was.
     */
    fun rewrite(values: Map<String, JsonElement?>): History {
        val rewritten = LinkedHashMap(current.values)
        values.forEach { (id, value) -> if (value == null) rewritten.remove(id) else rewritten[id] = value }
        if (rewritten == current.values) return this
        return copy(entries = entries.dropLast(1) + current.copy(values = rewritten))
    }

    /**
     * Drops every entry [keep] refuses, behind and ahead, for a place that stopped existing. Where none is left behind
     * the history starts over at [fallback]. Two entries of one page that come together become one.
     */
    fun retain(fallback: ConfigId, keep: (HistoryEntry) -> Boolean): History {
        if (reachable.all(keep)) return this
        val left = entries.filter(keep).ifEmpty { listOf(HistoryEntry(fallback)) }.oncePerPage()
        val next = (ahead.filter(keep) + left.last()).oncePerPage().dropLast(1)
        return copy(entries = left, ahead = next)
    }

    // Neighbours of one page are one step of the arrows that goes nowhere, which show never makes.
    private fun List<HistoryEntry>.oncePerPage(): List<HistoryEntry> =
        filterIndexed { at, entry -> at == lastIndex || entry.page != this[at + 1].page }
}
