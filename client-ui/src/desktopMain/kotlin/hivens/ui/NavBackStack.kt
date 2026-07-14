package hivens.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf

/**
 * Center-router navigation history. Replaces the launcher's old single
 * `mutableStateOf<Screen>` so a Back action pops to wherever the user actually
 * came from instead of each screen routing back to a hardcoded literal.
 *
 * Invariant: the stack is never empty -- entry 0 is always a top-level root.
 * Top-level destinations ([TOP_LEVEL], the nav-rail entries) reset the stack to
 * that single root: switching tabs is a fresh context, not an ever-growing push
 * history. Every other (detail) screen pushes, so Back unwinds the exact path
 * that opened it -- Browse -> pack detail -> installed detail backs out the same
 * way it was entered, rather than jumping to a fixed screen.
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
     * Show [screen]. Re-selecting the current screen is a no-op. A top-level
     * destination clears history and becomes the new root; any other screen is
     * pushed onto the stack. Either way the forward path is discarded -- moving
     * somewhere new branches off it.
     */
    fun navigate(screen: Screen) {
        if (screen == current) return
        forwardStack.clear()
        if (isTopLevel(screen)) entries.clear()
        entries.add(screen)
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

    companion object {
        // Nav-rail destinations. Switching among these resets the stack instead
        // of stacking, so the history can't grow without bound from tab hopping.
        // Wardrobe and About are nav-rail siblings too -- they reset, not stack.
        // Detail screens (ServerSettings, PackDetail, ...) and the Settings
        // drill-downs (ThemePicker, BackgroundSettings)
        // are absent by design -- they push and are unwound by Back.
        private val TOP_LEVEL: Set<Screen> = setOf(
            Screen.Home,
            Screen.Library,
            Screen.Browse,
            Screen.Profile,
            Screen.Wardrobe,
            Screen.Settings,
            Screen.About,
        )

        private fun isTopLevel(screen: Screen): Boolean = screen in TOP_LEVEL
    }
}
