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

    /** The screen currently shown: the top of the stack. */
    val current: Screen get() = entries.last()

    /** True when [back] has somewhere to pop to. */
    val canGoBack: Boolean get() = entries.size > 1

    /**
     * Show [screen]. Re-selecting the current screen is a no-op. A top-level
     * destination clears history and becomes the new root; any other screen is
     * pushed onto the stack.
     */
    fun navigate(screen: Screen) {
        if (screen == current) return
        if (isTopLevel(screen)) entries.clear()
        entries.add(screen)
    }

    /** Pop one entry. No-op at the root; returns whether anything was popped. */
    fun back(): Boolean {
        if (entries.size <= 1) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    companion object {
        // Nav-rail destinations. Switching among these resets the stack instead
        // of stacking, so the history can't grow without bound from tab hopping.
        // Detail screens (ServerSettings, PackDetail, ...) are absent by design
        // -- they push and are unwound by Back.
        private val TOP_LEVEL: Set<Screen> = setOf(
            Screen.Home,
            Screen.Library,
            Screen.Browse,
            Screen.Profile,
            Screen.Settings,
        )

        private fun isTopLevel(screen: Screen): Boolean = screen in TOP_LEVEL
    }
}
