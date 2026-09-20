package hivens.ui.editor.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Whether a widget preview can be rendered with a net under it, and whether the
 * net can also release what it caught.
 *
 * Compose forbids try/catch around a @Composable invocation, which is why the
 * palette's drag ghost is a label chip and not the widget: a widget that throws
 * mid-composition takes the whole shell with it, and the only recovery is a
 * remount. A gallery that renders sixty of them would be sixty chances at that.
 *
 * An off-screen scene is a different shape of call. Its composition runs inside
 * an ordinary function, so the throw reaches the caller. Two separate questions
 * follow, and the first two versions of this probe each answered one and got the
 * other wrong:
 *
 *  1. WHERE the net goes. Content passed to the constructor is composed there, so
 *     a net around render() alone catches nothing.
 *  2. WHETHER the scene can then be released. A net around the constructor
 *     catches the throw and loses the object with it, because the assignment
 *     never happens. An unclosed scene leaks its raster surface AND leaves a
 *     Recomposer registered as a global snapshot apply-observer for the life of
 *     the process, which every state write in the launcher then walks.
 *
 * Both are answered by building the scene empty and handing it its content
 * afterwards. Nothing of the caller's runs in the constructor, so it cannot
 * throw, and the reference is in hand before anything can.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OffscreenPreviewProbeTest {

    /** The shape the preview pipeline uses: construct empty, then content, then close. */
    private fun renderCatching(content: @Composable () -> Unit): Attempt {
        val scene = ImageComposeScene(width = 64, height = 64, density = Density(1f))
        val result = runCatching {
            scene.setContent(content)
            scene.render()
            Unit
        }
        val closed = runCatching { scene.close() }.isSuccess
        return Attempt(result, closed)
    }

    private data class Attempt(val result: Result<Unit>, val closed: Boolean)

    @Test
    fun `a well-behaved composable renders off screen`() {
        val scene = ImageComposeScene(width = 64, height = 64, density = Density(1f))
        try {
            scene.setContent { Box(Modifier.fillMaxSize().background(Color.Red)) }
            val image = scene.render()
            assertNotNull(image)
            assertTrue(image.width == 64 && image.height == 64)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a composable that throws is caught by the caller rather than escaping`() {
        val attempt = renderCatching { error("widget blew up mid-composition") }
        assertTrue(attempt.result.isFailure, "if this passes the gallery can render real widgets safely")
        assertTrue(
            attempt.result.exceptionOrNull()?.message?.contains("blew up") == true,
            "the original cause has to survive, or a failing widget cannot be named: " +
                "${attempt.result.exceptionOrNull()}",
        )
    }

    @Test
    fun `a scene whose content threw is still released`() {
        // The half a net around the constructor silently gets wrong. Nothing can
        // close what the constructor never returned, and the cost of missing it is
        // a global apply-observer per failed preview, for the life of the process.
        val attempt = renderCatching { error("widget blew up mid-composition") }
        assertTrue(attempt.result.isFailure)
        assertTrue(attempt.closed, "the scene leaked on the path this whole file exists to make safe")
    }

    @Test
    fun `a throw in one scene does not poison the next`() {
        renderCatching { error("first one fails") }
        val after = renderCatching { Box(Modifier.fillMaxSize().background(Color.Green)) }
        assertTrue(after.result.isSuccess, "a gallery renders every widget, so one bad one must not end the run")
        assertTrue(after.closed)
    }
}
