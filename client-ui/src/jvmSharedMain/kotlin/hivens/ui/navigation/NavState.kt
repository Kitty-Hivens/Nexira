package hivens.ui.navigation

import androidx.compose.runtime.Immutable
import hivens.ui.Screen

/**
 * A composition's history as a widget reads it: the screen shown, the path from the
 * root to it, and which way the arrows can go. A value, so the readers recompose
 * when the history moves and not when the composition does.
 */
@Immutable
data class NavState(
    val current: Screen,
    val trail: List<Screen>,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    /**
     * How many times back was asked for at the root, where the history had nowhere
     * left to go. Back never takes the person out of the app, so a composition that
     * wants something to happen there, a question before leaving for one, watches
     * this move. The
     * launcher's own composition does nothing with it.
     */
    val backsAtRoot: Int = 0,
)
