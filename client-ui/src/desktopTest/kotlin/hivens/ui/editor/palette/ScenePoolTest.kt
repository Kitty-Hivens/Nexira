package hivens.ui.editor.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the gallery saves by keeping its scenes, and what it refuses to keep.
 *
 * The saving is the reason the pool exists and it is easy to lose silently: a key
 * that varies per widget, or an eviction that fires on every render, puts the
 * gallery back to a scene per tile with every other test still green. So what is
 * asserted here is the count of constructions, not the outcome of a render.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ScenePoolTest {

    private val pool = ScenePool(Density(1f))

    @AfterTest
    fun release() {
        pool.close()
    }

    /** Draws something into a scene of [raster] and reports the size that came back. */
    private fun paint(raster: IntSize, color: Color = Color.Red): IntSize =
        pool.draw(raster) { scene ->
            scene.setContent { Box(Modifier.fillMaxSize().background(color)) }
            val image = scene.render()
            try {
                IntSize(image.width, image.height)
            } finally {
                image.close()
            }
        }

    private fun blowUp(raster: IntSize) =
        pool.draw(raster) { scene ->
            scene.setContent { error("widget blew up mid-composition") }
            scene.render().close()
        }

    @Test
    fun `the same raster is drawn into the same scene`() {
        repeat(5) { paint(IntSize(64, 48)) }
        assertEquals(1, pool.built, "five tiles of one size built five scenes, so nothing is being pooled")
        assertEquals(1, pool.held)
    }

    @Test
    fun `a different raster gets its own scene`() {
        // Not one oversized scene for everything: the ink trim walks the raster,
        // so a small widget drawn into a large frame pays for the whole frame.
        paint(IntSize(64, 48))
        paint(IntSize(200, 120))
        assertEquals(2, pool.built)
        assertEquals(2, pool.held)
    }

    @Test
    fun `the scene is the size it was keyed by`() {
        assertEquals(IntSize(37, 91), paint(IntSize(37, 91)))
        assertEquals(IntSize(200, 120), paint(IntSize(200, 120)))
    }

    @Test
    fun `a widget that throws reaches the caller`() {
        // The whole reason previews are drawn off screen. Compose forbids
        // try/catch around a composable invocation, so this is the only place a
        // failing widget can be caught instead of taking the shell down with it.
        val boom = assertFailsWith<IllegalStateException> { blowUp(IntSize(64, 48)) }
        assertTrue("blew up" in (boom.message ?: ""), "the cause has to survive or the widget cannot be named")
    }

    @Test
    fun `a scene whose composition threw is not handed to the next widget`() {
        val raster = IntSize(64, 48)
        paint(raster)
        assertEquals(1, pool.built)

        runCatching { blowUp(raster) }
        assertEquals(0, pool.held, "the spoiled scene is still held, so its leftovers go into the next preview")

        paint(raster, Color.Green)
        assertEquals(2, pool.built, "no fresh scene was built, so the next widget drew into the broken one")
    }

    @Test
    fun `one bad widget does not end the run`() {
        val raster = IntSize(64, 48)
        runCatching { blowUp(raster) }
        assertTrue(
            runCatching { paint(raster, Color.Green) }.isSuccess,
            "a gallery renders every widget, so one refusal must not stop the rest",
        )
    }

    @Test
    fun `close releases everything it held`() {
        // Each scene holds a skia surface and, while it lives, a Recomposer
        // registered as a process-wide snapshot apply-observer that every state
        // write in the launcher walks.
        paint(IntSize(64, 48))
        paint(IntSize(80, 80))
        assertEquals(2, pool.held)
        pool.close()
        assertEquals(0, pool.held)
    }

    @Test
    fun `a closed pool draws again rather than handing back a dead scene`() {
        paint(IntSize(64, 48))
        pool.close()
        assertTrue(
            runCatching { paint(IntSize(64, 48)) }.isSuccess,
            "reopening the gallery after it closed has to draw, not fail",
        )
    }
}
