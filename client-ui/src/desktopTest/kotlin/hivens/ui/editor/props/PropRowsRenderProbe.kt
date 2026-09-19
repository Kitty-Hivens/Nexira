package hivens.ui.editor.props

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.widget.model.PropColor
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * The prop panel's row vocabulary, drawn at the width it actually gets.
 *
 * Not an assertion. The panel is 320dp with a 14dp gutter, so its rows have 292dp
 * to divide between a label and a control, and every claim about that division is
 * a claim about pixels: whether the controls line up, whether the labels are the
 * loudest thing on a panel that carries its own heading, how much of the width a
 * slider's track is left with. Reading the numbers out of the source answers none
 * of it, which is how a panel ends up with a switch aligned one way, four fields
 * another and two sliders a third.
 *
 * One field per control the dispatch in [PropFieldRow] can reach, so the sheet is
 * the whole vocabulary rather than a sample of it.
 */
class PropRowsRenderProbe {

    @Serializable
    private enum class ProbeFit { Cover, Contain, Stretch }

    @Serializable
    private data class ProbeProps(
        @PropLabel("Heading") val heading: String = "Now playing",
        @PropLabel("Show cover") val showCover: Boolean = true,
        @PropLabel("Corner") @PropRange(0.0, 40.0) val corner: Int = 12,
        @PropLabel("Tint") @PropRange(0.0, 1.0) val tint: Float = 0.24f,
        @PropLabel("Accent") @PropColor val accent: String = "#BB86FC",
        @PropLabel("Fit") val fit: ProbeFit = ProbeFit.Cover,
        @PropLabel("Columns") val columns: Int = 3,
    )

    @Test
    fun `the row vocabulary on a dark panel`() = sheet(dark = true)

    @Test
    fun `the row vocabulary on a light panel`() = sheet(dark = false)

    /**
     * The panel's plane, hand-rolled beside the library's.
     *
     * The three docked panels each drew `shadow + clip + background(surface)`, which
     * is a plane without the one thing the library's has: a bevel hairline derived
     * from the body's own luminance. On a dark palette a panel body and the page
     * behind it are four L* apart, and four is what an edge is for. Drawn side by
     * side because that difference is not arguable from the source, where both are
     * "a rounded rect in the surface colour".
     */
    @Test
    fun `the panel plane, hand-rolled beside the library's`() {
        planes(dark = true)
        planes(dark = false)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun planes(dark: Boolean) {
        val scene = ImageComposeScene(width = 560, height = 320, density = Density(2f)) {
            NxTheme(useDarkTheme = dark) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        // What the panels drew: the page's own surface rung, a cast
                        // shadow, and no edge of their own.
                        Box(
                            Modifier
                                .size(110.dp, 120.dp)
                                .shadow(18.dp, MaterialTheme.shapes.large)
                                .clip(MaterialTheme.shapes.large)
                                .background(NxTheme.colors.surface),
                        )
                        // What they draw now: the floating rung, the same shadow, and
                        // a hairline lifted off the body.
                        NxSurface(
                            level    = NxSurfaceLevel.Floating,
                            shape    = MaterialTheme.shapes.large,
                            opacity  = 1f,
                            blurDp   = 0f,
                            shadowDp = PANEL_SHADOW_DP,
                            modifier = Modifier.size(110.dp, 120.dp),
                        ) {}
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()
        File(OUT).mkdirs()
        val name = "panel-plane-${if (dark) "dark" else "light"}.png"
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(OUT, name).writeBytes(it) }
        println("PropRowsRenderProbe: $OUT/$name, hand-rolled left, library right")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(dark: Boolean) {
        val descriptor = serializer<ProbeProps>().descriptor
        val values: JsonObject = defaults()

        val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(DENSITY)) {
            NxTheme(useDarkTheme = dark) {
                // The page behind the panel, so the plane's own edge is visible rather
                // than being the same colour as everything around it.
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
                    Column(
                        modifier = Modifier
                            .width(PANEL_DP.dp)
                            .background(NxTheme.colors.surface)
                            .padding(horizontal = GUTTER_DP.dp, vertical = GUTTER_DP.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        for (i in 0 until descriptor.elementsCount) {
                            val name = descriptor.getElementName(i)
                            PropFieldRow(
                                label       = descriptor.getElementAnnotations(i)
                                    .filterIsInstance<PropLabel>().firstOrNull()?.value ?: name,
                                element     = descriptor.getElementDescriptor(i),
                                annotations = descriptor.getElementAnnotations(i),
                                current     = values.getValue(name),
                                onChange    = {},
                            )
                        }
                    }
                }
            }
        }

        val image = scene.render()
        scene.close()
        File(OUT).mkdirs()
        val name = "prop-rows-${if (dark) "dark" else "light"}.png"
        image.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(OUT, name).writeBytes(it) }
        println("PropRowsRenderProbe: $OUT/$name at ${WIDTH}x$HEIGHT, panel ${PANEL_DP}dp, gutter ${GUTTER_DP}dp")
    }

    /** The effective values the panel would show, one per declared field. */
    private fun defaults(): JsonObject = JsonObject(
        mapOf<String, JsonElement>(
            "heading" to JsonPrimitive("Now playing"),
            "showCover" to JsonPrimitive(true),
            "corner" to JsonPrimitive(12),
            "tint" to JsonPrimitive(0.24f),
            "accent" to JsonPrimitive("#BB86FC"),
            "fit" to JsonPrimitive("Cover"),
            "columns" to JsonPrimitive(3),
        ),
    )

    private companion object {
        // The panel's real geometry: 320dp wide with a 14dp horizontal gutter.
        const val PANEL_DP = 320
        const val GUTTER_DP = 14
        // Two, so the sheet is readable rather than a thumbnail of itself.
        const val DENSITY = 2f
        const val WIDTH = (PANEL_DP + 80) * 2
        const val HEIGHT = 440 * 2
        const val OUT = "build/render"
    }
}
