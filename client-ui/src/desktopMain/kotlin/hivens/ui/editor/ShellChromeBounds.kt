package hivens.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * Where the shell's content pane actually landed, in window pixels.
 *
 * The editor's overlays have to sit over that pane rather than over the rails,
 * and they used to do it with two constants: 65dp of left rail and the right
 * rail's default width. Both are props. A rail folded away, widened or narrowed
 * left the overlays measuring against a number nobody had updated, so the panels
 * drifted sideways by whatever the difference was, and a collapsed right rail
 * left them a whole rail short of the edge.
 *
 * Measured rather than derived, because the answer is not in the props either: a
 * rail with no named width takes a weight of the row, and reproducing that here
 * would be a second copy of the shell's layout rules kept in step by hand.
 *
 * Null means "not reported yet", which is the first frame and any build with no
 * centre region. The reader falls back to the space it has.
 */
@Stable
class ShellChromeBounds {
    /** The centre region's rectangle: the pane the editor's overlays belong over. */
    var center: Rect? by mutableStateOf(null)
}

/**
 * Static because the holder itself never changes, only the value on it: a reader
 * of it recomposes, and everything else under the shell does not.
 */
val LocalShellChromeBounds = staticCompositionLocalOf { ShellChromeBounds() }
