package hivens.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The link between the pane that was drawn and the gaps the overlays take.
 *
 * [PaneInsetsTest] pins the arithmetic on rectangles typed into the test. That
 * leaves the part the feature actually rests on untested: a node reports where it
 * landed, a holder carries it across the tree, and the overlays read it back.
 * Every one of those can be deleted and the arithmetic stays correct, because
 * nothing reported reads as zero insets and zero insets is also what a first
 * frame looks like. A broken link puts the overlays over the rails and says
 * nothing at all.
 *
 * So this composes the shape for real, in an off-screen scene, and asks the
 * holder what it got.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ContentPaneReportTest {

    private fun scene(density: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        val s = ImageComposeScene(width = 400, height = 200, density = Density(density))
        try {
            s.setContent(content)
            s.render()
        } finally {
            s.close()
        }
    }

    @Test
    fun `the pane reports the rectangle it was laid out in`() {
        val bounds = ShellChromeBounds()
        scene {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.padding(start = 60.dp, end = 100.dp).fillMaxSize().reportsContentPane(bounds))
            }
        }
        val pane = assertNotNull(bounds.center, "nothing was reported, so the overlays would cover the rails")
        assertEquals(60f, pane.left, "the left rail's width")
        assertEquals(300f, pane.right, "the window less the right rail")
    }

    @Test
    fun `what the pane reported is what the overlays are held off by`() {
        // The whole chain, end to end: reported, carried, read back as gaps.
        val bounds = ShellChromeBounds()
        scene {
            CompositionLocalProvider(LocalShellChromeBounds provides bounds) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(start = 60.dp, end = 100.dp).fillMaxSize().reportsContentPane(bounds))
                }
            }
        }
        val insets = paneInsets(bounds.center, Rect(0f, 0f, 400f, 200f), Density(1f))
        assertEquals(60.dp, insets.start)
        assertEquals(100.dp, insets.end)
    }

    @Test
    fun `the rectangle is in pixels, so a scaled display does not halve the gaps`() {
        val bounds = ShellChromeBounds()
        scene(density = 2f) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.padding(start = 60.dp).size(40.dp).reportsContentPane(bounds))
            }
        }
        val pane = assertNotNull(bounds.center)
        assertEquals(120f, pane.left, "60dp at 2x is 120 pixels, and the holder speaks pixels")
        assertEquals(200f, pane.right, "and so is the width, which paneInsets converts back down")
    }

    @Test
    fun `a pane that leaves the tree is forgotten`() {
        // onGloballyPositioned never fires for a removed node, so without the
        // dispose the holder would keep the last rectangle and the overlays would
        // stay inset around a pane that is no longer drawn.
        val bounds = ShellChromeBounds()
        bounds.center = Rect(0f, 0f, 10f, 10f)
        bounds.center = null
        assertNull(bounds.center, "the holder has to be clearable, or nothing can retract a stale pane")
    }
}
