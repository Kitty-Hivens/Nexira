package hivens.ui.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

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
 * Measured rather than derived, because the answer is not in the props either.
 * A rail animates its width open and shut, folds itself away below a window it
 * cannot lay out in, and sits inside an inset of its own, so reproducing where it
 * ends up would be a second copy of the shell's layout rules kept in step by hand.
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

/**
 * Marks this node as the content pane, so the editor's overlays can find it.
 *
 * Named rather than written inline at the one call site, because it is one link
 * of a three-link chain -- report, provide, read -- whose failure mode is silence:
 * with nothing reported the insets are zero, which is also what a first frame
 * looks like, so a broken link leaves the overlays full-screen and says nothing.
 * A name is something a test can hold and a reader can grep for.
 */
fun Modifier.reportsContentPane(bounds: ShellChromeBounds): Modifier =
    onGloballyPositioned { bounds.center = it.boundsInWindow() }
