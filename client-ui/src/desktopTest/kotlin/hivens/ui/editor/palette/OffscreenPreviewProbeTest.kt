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
 * Whether a widget preview can be rendered with a net under it.
 *
 * Compose forbids try/catch around a @Composable invocation, which is why the
 * palette's drag ghost is a label chip and not the widget: a widget that throws
 * mid-composition takes the whole shell with it, and the only recovery is a
 * remount. A gallery that renders sixty of them would be sixty chances at that.
 *
 * An off-screen scene is a different shape of call. The composition runs inside
 * `render()`, which is an ordinary function, so the question is whether a throw
 * from a composable reaches the caller as a catchable exception or escapes some
 * other way. Everything a real preview pipeline would be built on rests on the
 * answer, so it is measured here rather than assumed.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OffscreenPreviewProbeTest {

    /**
     * The construction is inside the net, not just the render.
     *
     * ImageComposeScene composes its content in its CONSTRUCTOR, by way of
     * setContent, so a widget that throws has already thrown before render() is
     * reached. A net around render() alone catches nothing, which is what the
     * first version of this probe measured.
     *
     * close() rather than use(): the type is not Closeable, and a scene whose
     * composition threw still holds skiko resources, so the release has to happen
     * on the failure path too.
     */
    private fun renderCatching(content: @Composable () -> Unit): Result<Unit> = runCatching {
        var scene: ImageComposeScene? = null
        try {
            scene = ImageComposeScene(width = 64, height = 64, density = Density(1f)) { content() }
            scene.render()
        } finally {
            runCatching { scene?.close() }
        }
    }

    @Test
    fun `a well-behaved composable renders off screen`() {
        val scene = ImageComposeScene(width = 64, height = 64, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.Red))
        }
        val image = scene.render()
        scene.close()
        assertNotNull(image)
        assertTrue(image.width == 64 && image.height == 64)
    }

    @Test
    fun `a composable that throws is caught by the caller rather than escaping`() {
        val result = renderCatching { error("widget blew up mid-composition") }
        assertTrue(result.isFailure, "if this passes the gallery can render real widgets safely")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("blew up") == true,
            "the original cause has to survive, or a failing widget cannot be named: " +
                "${result.exceptionOrNull()}",
        )
    }

    @Test
    fun `a throw in one scene does not poison the next`() {
        renderCatching { error("first one fails") }
        val after = renderCatching { Box(Modifier.fillMaxSize().background(Color.Green)) }
        assertTrue(after.isSuccess, "a gallery renders every widget, so one bad one must not end the run")
    }
}
