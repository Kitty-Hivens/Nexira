package hivens.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

/**
 * Center-router navigation history. Replaces the launcher's old single
 * `mutableStateOf<Screen>` so a Back action pops to wherever the user actually
 * came from instead of each screen routing back to a hardcoded literal.
 *
 * Invariant: the stack is never empty -- entry 0 is always a root.
 *
 * Resetting belongs to the ACTION, not to the destination. [switchTo] is what the
 * nav rail does: it clears the history and makes the target the new root, because
 * switching tabs is a fresh context rather than an ever-growing push history.
 * [navigate] pushes, so Back unwinds the exact path that opened a screen -- Browse
 * -> pack detail -> installed detail backs out the way it was entered.
 *
 * They used to be one call deciding by a set of destinations, which worked until a
 * screen appeared in both roles. About is a rail entry AND a link inside Settings;
 * reaching it from Settings therefore wiped the history, so its Back arrow greyed
 * out and the only way on was the rail, which also lost the settings category the
 * reader had been in. A destination cannot know which of the two it was.
 */
@Stable
class NavBackStack(root: Screen) {
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

    /** True when [back] has somewhere to pop to. */
    val canGoBack: Boolean get() = entries.size > 1

    /** True when [forward] can re-apply a screen that [back] / [popTo] popped. */
    val canGoForward: Boolean get() = forwardStack.isNotEmpty()

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
     * Switch to [screen] as a fresh context: history cleared, [screen] the new root.
     * What the nav rail does, so hopping among its entries cannot pile up a
     * "Profile > Wardrobe > About > Wardrobe" trail.
     */
    fun switchTo(screen: Screen) {
        forwardStack.clear()
        if (screen == current && entries.size == 1) return
        entries.clear()
        entries.add(screen)
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

    /** Pop one entry onto the forward stack. No-op at the root; returns whether
     *  anything was popped. */
    fun back(): Boolean {
        if (entries.size <= 1) return false
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

}
