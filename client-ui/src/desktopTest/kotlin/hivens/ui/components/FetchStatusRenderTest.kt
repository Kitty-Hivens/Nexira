package hivens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.media.MediaFetch
import hivens.ui.i18n.EnglishStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What a viewer sees while a video is being fetched. Every phase is rendered
 * over the dark placeholder the real one draws on; the sheet lands under
 * build/render.
 *
 * The assertion is the one thing that was wrong before any of this had a
 * readout: a measured phase must ink its measure differently from an unmeasured
 * one, so "45% of 40 MB" and "size unknown" cannot look like the same wait.
 */
class FetchStatusRenderTest {

    private val width = 360
    private val height = 150

    private var progressAccent: Color = Color.Unspecified

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(name: String, style: StyleSpec = CelestiaStyle, scale: Float = 1f, content: @Composable () -> Unit): Bitmap {
        val scene = ImageComposeScene(
            width   = (width * scale).toInt(),
            height  = (height * scale).toInt(),
            density = Density(scale),
        ) {
            NxTheme(useDarkTheme = true, style = style) {
                CompositionLocalProvider(
                    LocalStyle provides style,
                    LocalStrings provides EnglishStrings,
                ) {
                    progressAccent = NxTheme.colors.progressAccent
                    Column(
                        modifier            = Modifier.fillMaxSize().background(Color(0xFF101014)).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // The real placeholder centres its overlay; a sheet that
                        // left-aligned it would show a layout the app never draws.
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { content() }
                }
            }
        }
        // Two frames, not one. The first starts the clock; an indeterminate sweep
        // begins its segment off the left edge of the track and draws nothing at
        // all at time zero, so a single frame would show an empty bar and say
        // nothing about whether the measure works.
        scene.render(0L)
        val image = scene.render(SETTLE_NANOS)
        scene.close()
        val out = File("build/render").apply { mkdirs() }
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(out, "video-fetch-$name@${scale.toInt()}x.png").writeBytes(it) }
        return Bitmap.makeFromImage(image)
    }

    private fun accentPixels(bmp: Bitmap): Int {
        var hits = 0
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (near(bmp.getColor(x, y), progressAccent.toArgbInt(), tolerance = 20)) hits++
            }
        }
        return hits
    }

    @Test
    fun `every phase of the wait renders`() {
        val phases = listOf(
            "installing-tool" to MediaFetch.InstallingTool(),
            "installing-tool-measured" to MediaFetch.InstallingTool(doneBytes = 12L * 1024 * 1024, totalBytes = 30L * 1024 * 1024),
            "resolving" to MediaFetch.Resolving,
            "downloading" to MediaFetch.Downloading(doneBytes = 18L * 1024 * 1024, totalBytes = 40L * 1024 * 1024),
            "downloading-unknown" to MediaFetch.Downloading(doneBytes = 3L * 1024 * 1024, totalBytes = 0L),
        )
        for ((name, fetch) in phases) {
            render(name, scale = 2f) { FetchStatus(fetch) {} }
            val bmp = render(name) { FetchStatus(fetch) {} }
            assertTrue(accentPixels(bmp) > 0, "$name: the measure must draw something")
        }
    }

    @Test
    fun `a measured download inks less than a finished one`() {
        fun inked(done: Long, total: Long, name: String) =
            accentPixels(render(name) { FetchStatus(MediaFetch.Downloading(done, total)) {} })

        val quarter = inked(10L * 1024 * 1024, 40L * 1024 * 1024, "measure-quarter")
        val whole = inked(40L * 1024 * 1024, 40L * 1024 * 1024, "measure-whole")
        assertTrue(whole > quarter, "the bar must grow with the download: $quarter -> $whole")
    }

    /** Far enough into the sweep to be on the track, short of a full lap. */
    private val SETTLE_NANOS = 400_000_000L

    private fun Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    private fun near(a: Int, b: Int, tolerance: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) < tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) < tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) < tolerance
}
