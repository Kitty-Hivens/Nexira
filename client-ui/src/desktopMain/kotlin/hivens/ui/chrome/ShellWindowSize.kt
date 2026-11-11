package hivens.ui.chrome

import java.awt.Dimension

// Frame sizes the shell asks of the window, in AWT user space, which is the dp
// value 1:1 for frame geometry.

/**
 * The window's minimum size: the 2-column Library + sidebar starts collapsing
 * visibly below this width, and 600dp of height keeps PackDetail hero + sidebar
 * both reachable.
 */
val MIN_SHELL_SIZE = Dimension(960, 600)

/**
 * What a window born on a very small work area is raised to (see
 * [hivens.window.initialWindowSize]): below this the rails and the centre pane
 * stop fitting side by side.
 */
val OPENING_FLOOR = Dimension(800, 600)
