package hivens.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/**
 * Center-router navigation history. Replaces the launcher's old single
 * `mutableStateOf<Screen>` so a Back action pops to wherever the user actually
 * came from instead of each screen routing back to a hardcoded literal.
 *
 * Invariant: the stack is never empty -- entry 0 is always a root.
 *
 * Back walks the history until it reaches home, the root the stack was made with,
 * and never further: what happens when it is asked for at home is the composition's
 * business, which [backsAtRoot] tells it about. Leaving the app is not one of the
 * answers, whatever host the history lives in.
 *
 * Resetting belongs to the ACTION, not to the destination. [switchTo] is what the
 * nav rail does: the history becomes home and the target, because switching tabs
 * is a fresh context rather than an ever-growing push history, while back from a
 * rail section still has somewhere to go. It used to clear everything and make the
 * target the new root, which left back from Library nowhere to go, and on a phone
 * a back with nowhere to go took the person out of the launcher. [navigate] pushes, so Back unwinds
 * the exact path that opened a screen -- Browse -> pack detail -> installed detail
 * backs out the way it was entered.
 *
 * They used to be one call deciding by a set of destinations, which worked until a
 * screen appeared in both roles. About is a rail entry AND a link inside Settings;
 * reaching it from Settings therefore wiped the history, so its Back arrow greyed
 * out and the only way on was the rail, which also lost the settings category the
 * reader had been in. A destination cannot know which of the two it was.
 */
@Stable
class NavBackStack(root: Screen) {
    // What a rail switch keeps under the screen it opens, so back from any rail
    // section comes home.
    private val home = root

    private val entries = mutableStateListOf(root)

    // Screens popped by back() / popTo(), available to forward(). Cleared by
    // navigate(): moving to a new screen invalidates the old forward path
    // (standard browser back/forward semantics).
    private val forwardStack = mutableStateListOf<Screen>()

    /** The screen currently shown: the top of the stack. */
    val current: Screen get() = entries.last()

    /**
     * Root-to-current path, for the top-bar breadcrumb. Read-only snapshot --
     * mutate only through [navigate] / [back] / [popTo]. Snapshot-backed, so
     * reads in composition recompose when the stack changes.
     */
    val trail: List<Screen> get() = entries.toList()

    /**
     * Every screen still reachable by the arrows: the [trail], then what [forward]
     * would re-apply. A screen that leaves this has left the history for good.
     */
    val history: List<Screen> get() = entries + forwardStack

    /** True when [back] has somewhere to pop to. */
    val canGoBack: Boolean get() = entries.size > 1

    /** True when [forward] can re-apply a screen that [back] / [popTo] popped. */
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

    /**
     * How many times [back] was asked for at the root, where the history had
     * nowhere left to go. The host never lets such a back take the person out of
     * the app, so this is how whatever the composition wants to happen there hears
     * of it: a question
     * before leaving, say, which watches the count move. Nothing in the default
     * composition does, and back at the root simply does nothing there.
     */
    var backsAtRoot: Int by mutableIntStateOf(0)
        private set

    /**
     * Go to [screen], keeping the way back. Re-selecting the current screen is a
     * no-op; the forward path is discarded, because moving somewhere new branches
     * off it.
     */
    fun navigate(screen: Screen) {
        if (screen == current) return
        forwardStack.clear()
        entries.add(screen)
    }

    /**
     * Switch to [screen] as a fresh context: the history becomes home and [screen],
     * or home alone when [screen] is home. What the nav rail does, so hopping among
     * its entries cannot pile up a "Profile > Wardrobe > About > Wardrobe" trail,
     * and back from any of them comes home. The forward path is discarded.
     */
    fun switchTo(screen: Screen) {
        forwardStack.clear()
        val fresh = if (screen == home) listOf(home) else listOf(home, screen)
        if (entries.toList() == fresh) return
        entries.clear()
        entries.addAll(fresh)
    }

    /**
     * Swap the top entry in place, keeping history and the forward path intact.
     * For a screen re-describing itself before a push -- e.g. PackDetail marking
     * that its settings overlay must restore when Back returns to it -- not for
     * navigation; use [navigate] to actually go somewhere.
     */
    fun replaceCurrent(screen: Screen) {
        entries[entries.lastIndex] = screen
    }

    /**
     * Pop one entry onto the forward stack, returning whether anything was popped.
     * At the root nothing moves, and the press is counted in [backsAtRoot].
     */
    fun back(): Boolean {
        if (entries.size <= 1) {
            backsAtRoot++
            return false
        }
        forwardStack.add(entries.removeAt(entries.lastIndex))
        return true
    }

    /** Re-apply the screen most recently popped by [back] / [popTo]. No-op when
     *  the forward stack is empty; returns whether anything moved. */
    fun forward(): Boolean {
        val screen = forwardStack.removeLastOrNull() ?: return false
        entries.add(screen)
        return true
    }

    /**
     * Pop back to [screen] (a breadcrumb-segment click). Moves every entry above
     * the deepest occurrence of [screen] onto the forward stack (so [forward] can
     * re-expand the path). No-op when it is the current screen or not on the stack
     * -- a stale crumb must not corrupt history.
     */
    fun popTo(screen: Screen) {
        val idx = entries.lastIndexOf(screen)
        if (idx < 0 || idx == entries.lastIndex) return
        while (entries.lastIndex > idx) forwardStack.add(entries.removeAt(entries.lastIndex))
    }

    /**
     * Drops every entry, back and forward, that [keep] refuses, for a destination
     * that stopped existing: a screen somebody deleted while it sat in the history.
     * Going back to it would open a page about nothing. When nothing is left the
     * stack starts over at [fallback], so the never-empty invariant holds.
     */
    fun retainWhere(fallback: Screen, keep: (Screen) -> Boolean) {
        if (entries.all(keep) && forwardStack.all(keep)) return
        forwardStack.removeAll { !keep(it) }
        entries.removeAll { !keep(it) }
        if (entries.isEmpty()) entries.add(fallback)
    }

}
