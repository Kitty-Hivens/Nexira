package hivens.ui.render

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen render of a pack description through the whole markdown -> HTML ->
 * Compose path, under both styles. ImageComposeScene rasterises with no display,
 * so it is isolated from any live session; it guards the renderer from a
 * compose-time crash and dumps a PNG under build/ for a manual look at the
 * typography, which is the part no assertion can judge.
 *
 * The body is deliberately every construct the renderer handles rather than a
 * realistic description: a table with no column rules and a `details` that does
 * not fold both look fine in prose that never uses them.
 */
class MarkdownRenderTest {

    private val body = """
        # Remarkably Optimized

        A performance pack that keeps the vanilla feel. Built for people who want
        their frames back without their game looking like a different game.

        ## What it changes

        The renderer is replaced wholesale, the lighting engine is threaded, and
        entity culling is on by default. Nothing here touches world generation.

        - Sodium and its companions, configured rather than merely bundled
        - Shader support through Iris, off until you turn it on
        - No telemetry, no account checks, no phone-home

        ### Requirements

        | Component | Minimum | Recommended |
        | --- | --- | --- |
        | Memory | 4 GB | 8 GB |
        | Java | 21 | 25 |
        | GPU | OpenGL 4.3 | Vulkan-capable |

        > Read the notes before updating across a Minecraft version. A world is
        > not always portable and the pack cannot make it so.

        ## Configuration

        Drop this into `config/sodium-options.json` if you want the old defaults:

        ```
        { "quality": { "weather_quality": "FAST", "leaves_quality": "FAST" }, "advanced": { "enable_memory_tracing": false } }
        ```

        <details>
        <summary>Full mod list</summary>

        Sodium, Lithium, Starlight, FerriteCore, ImmediatelyFast, EntityCulling,
        ModernFix, Iris, Continuity, Indium.

        </details>

        <details open>
        <summary>Known issues</summary>

        Iris and Continuity disagree about connected glass on some drivers. Turn
        Continuity off if windows render as solid blocks.

        </details>

        ---

        Licensed under LGPL. Report issues on the tracker.
    """.trimIndent()

    private fun render(style: StyleSpec, name: String) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(1100, 1600, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = style) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                    Box(Modifier.verticalScroll(rememberScrollState()).padding(32.dp)) {
                        MarkdownHtml(markdown = body, onLink = {})
                    }
                }
            }
        }
        val painted: Double
        try {
            var frameNanos = 0L
            repeat(10) {
                scene.render(frameNanos)
                frameNanos += 16_000_000L
            }
            val frame = scene.render(frameNanos)
            Files.write(out, frame.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("PNG encode failed"))
            painted = inkFraction(frame)
        } finally {
            scene.close()
        }
        assertTrue(painted > MIN_INK, "only ${(painted * 100).toInt()}% of the page differs from its ground -- nothing rendered")
    }

    @Test fun `renders a pack description under Celestia`() = render(CelestiaStyle, "markdown-celestia.png")

    @Test fun `renders a pack description under Brut`() = render(BrutStyle, "markdown-brut.png")

    /** Share of sampled pixels that are not the page ground -- text, rules, panels. */
    private fun inkFraction(frame: Image): Double {
        val bmp = Bitmap.makeFromImage(frame)
        val ground = bmp.getColor(2, 2)
        var ink = 0
        var sampled = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getColor(x, y) != ground) ink++
                sampled++
                x += 2
            }
            y += 2
        }
        return ink.toDouble() / sampled
    }

    private companion object {
        /** Prose is mostly ground; a page with text on it still clears a few percent. */
        const val MIN_INK = 0.02
    }
}
