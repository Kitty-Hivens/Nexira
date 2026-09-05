package hivens.ui.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Moving the pointer must not recompose the wallpaper.
 *
 * It did. The translation was derived in the composable body, which makes reading
 * the pointer a snapshot read during composition, so every mouse move recomposed
 * the whole background subtree -- the video player included. The transform itself
 * was never the cost: graphicsLayer applies it at composite time over pixels that
 * are already drawn.
 *
 * The scene is built and driven on the AWT event thread. Compose's snapshot
 * apply-notifications are pumped there on desktop, and this composition collects a
 * snapshotFlow, so driving render from the test's own thread puts two threads in
 * one scene and they take the frame-clock and dispatcher locks in opposite orders.
 * See ThresholdOverlayRenderTest for the full account of that hang.
 */
class ParallaxRecompositionTest {

    @Test
    fun `pointer movement animates without recomposing`() = onAwtThread {
        var mouse by mutableStateOf(Offset(0.5f, 0.5f))
        var compositions = 0
        var offset: ParallaxOffset? = null

        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(64, 64, density = Density(1f)) {
            compositions++
            val p = rememberParallaxOffset({ mouse }, intensity = 1f)
            offset = p
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = p?.x?.value ?: 0f
                    translationY = p?.y?.value ?: 0f
                },
            )
        }
        try {
            var t = 0L
            fun frame() { scene.render(t); t += FRAME }
            repeat(4) { frame() }
            val settled = compositions

            // A corner: the largest translation the mapping can produce.
            mouse = Offset(0f, 0f)
            repeat(60) { frame() }

            assertEquals(settled, compositions, "pointer movement recomposed the wallpaper")
            val moved = abs(offset!!.x.value) + abs(offset!!.y.value)
            assertTrue(moved > 1f, "the spring never ran: translation is $moved")
        } finally {
            scene.close()
        }
    }

    /**
     * The pointer moving is the only way parallax is ever used, and a single jump does
     * not exercise it: one emission cannot be cancelled by a next one. Driven a frame
     * at a time, the spring was cancelled by every emission before it was handed a
     * frame, and the offset stayed at exactly zero for as long as the pointer moved.
     */
    @Test
    fun `parallax keeps up with a pointer that is still moving`() = onAwtThread {
        var mouse by mutableStateOf(Offset(0.5f, 0.5f))
        var compositions = 0
        var offset: ParallaxOffset? = null

        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(64, 64, density = Density(1f)) {
            compositions++
            val p = rememberParallaxOffset({ mouse }, intensity = 1f)
            offset = p
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = p?.x?.value ?: 0f
                    translationY = p?.y?.value ?: 0f
                },
            )
        }
        try {
            var t = 0L
            fun frame() { scene.render(t); t += FRAME }
            repeat(4) { frame() }
            val settled = compositions

            // A new position every frame, the way a pointer actually travels.
            val steps = 60
            repeat(steps) { i ->
                mouse = Offset(0.5f - 0.5f * (i + 1) / steps, 0.5f)
                frame()
            }

            assertEquals(settled, compositions, "pointer movement recomposed the wallpaper")
            val moved = abs(offset!!.x.value) + abs(offset!!.y.value)
            assertTrue(moved > 10f, "the spring did not follow a moving pointer: translation is $moved")
        } finally {
            scene.close()
        }
    }

    @Test
    fun `parallax off builds no spring at all`() = onAwtThread {
        var built = true
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(16, 16, density = Density(1f)) {
            built = rememberParallaxOffset({ Offset(0.5f, 0.5f) }, intensity = 0f) != null
            Box(Modifier.fillMaxSize())
        }
        try {
            scene.render(0L)
            assertTrue(!built, "parallax at zero intensity still built a spring")
        } finally {
            scene.close()
        }
    }

    private fun onAwtThread(body: () -> Unit) {
        val outcome = arrayOfNulls<Result<Unit>>(1)
        SwingUtilities.invokeAndWait { outcome[0] = runCatching(body) }
        outcome[0]!!.getOrThrow()
    }

    private companion object {
        const val FRAME = 16_000_000L
    }
}
