package hivens.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.activity.Activity
import hivens.core.activity.ActivityAction
import hivens.core.activity.ActivityKind
import hivens.core.activity.ActivityPhase
import hivens.ui.i18n.EnglishStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.BrutStyle
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
 * Sheet of the pill's states across both styles and both palettes, plus the two
 * measurements worth pinning: the body separates from whatever it floats over,
 * and the perimeter measure actually advances with the value.
 *
 * The widget itself pulls its subject from the registry through Koin, so what is
 * exercised here is the presentation -- the same [Pill] the widget composes,
 * handed activities directly. A PNG lands under build/render for eyeballing.
 */
class ActivityPillRenderTest {

    private val width = 980
    private val rowHeight = 76

    private fun activity(
        key: String,
        kind: ActivityKind,
        title: String,
        phase: ActivityPhase,
        actions: Set<ActivityAction> = emptySet(),
    ) = Activity(
        key = key,
        kind = kind,
        title = title,
        iconUrl = null,
        phase = phase,
        startedAtMillis = 0,
        updatedAtMillis = 0,
        actions = actions,
    )

    private val running = activity(
        "install:Industrial", ActivityKind.Install, "Industrial",
        ActivityPhase.Running(34, 97, "AmbientSounds.jar"), setOf(ActivityAction.Cancel),
    )
    private val game = activity(
        "game:SkyBlock", ActivityKind.Game, "SkyBlock",
        ActivityPhase.Running(0, 0), setOf(ActivityAction.Stop),
    )
    private val failed = activity(
        "install:Create", ActivityKind.Install, "Create", ActivityPhase.Failed("timeout"),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    /** Accent captured out of the real theme, so the probe cannot drift from it. */
    private var accent: Color = Color.Unspecified

    private fun render(
        style: StyleSpec,
        dark: Boolean,
        ground: Color,
        name: String,
        content: @Composable () -> Unit,
    ): Bitmap {
        val scene = ImageComposeScene(width = width, height = rowHeight * 3, density = Density(2f)) {
            // The real theme entry point rather than a hand-provided palette: what
            // the sheet shows is then what the app resolves, tonal ladder included.
            NxTheme(useDarkTheme = dark, style = style) {
                CompositionLocalProvider(
                    LocalStyle provides style,
                    LocalStrings provides EnglishStrings,
                ) {
                    accent = NxTheme.colors.progressAccent
                    Column(
                        modifier = Modifier.fillMaxSize().background(ground).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content() }
                }
            }
        }
        val image = scene.render()
        scene.close()
        val out = File("build/render").apply { mkdirs() }
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(out, "activity-pill-$name.png").writeBytes(it) }
        return Bitmap.makeFromImage(image)
    }

    @Composable
    private fun Sheet(props: PillProps) {
        val commands: ActivityCommands? = null // static sheet: nothing to click
        Box(Modifier.height(44.dp)) { Pill(running, props, commands, EnglishStrings) }
        Box(Modifier.height(44.dp)) { Pill(game, props, commands, EnglishStrings) }
        Box(Modifier.height(44.dp)) { Pill(failed, props, commands, EnglishStrings) }
    }

    @Test
    fun `the body separates from the ground it floats over`() {
        // The pill's whole premise is that it reads over arbitrary content, so
        // the one thing that must hold in every combination is that its body is
        // not the same tone as what is behind it. A near-black ground is the
        // hardest case -- it is the default with no wallpaper (#454).
        val cases = listOf(
            Triple("celestia-dark", CelestiaStyle to true, Color(0xFF121212)),
            Triple("brut-dark", BrutStyle to true, Color(0xFF121212)),
            Triple("celestia-light", CelestiaStyle to false, Color(0xFFF5F7FA)),
        )
        for ((name, styling, ground) in cases) {
            val (style, dark) = styling
            val bmp = render(style, dark, ground, name) { Sheet(PillProps()) }
            // Sample inside the first pill's body, past the leading icon.
            val body = bmp.getColor(300, 76)
            val bg = bmp.getColor(width - 20, 20)
            assertTrue(
                !near(body, bg, tolerance = 10),
                "pill body must not match the ground: body=${hex(body)} ground=${hex(bg)}",
            )
        }
    }

    @Test
    fun `the perimeter measure advances with the value`() {
        fun accentPixels(done: Long): Int {
            val one = activity(
                "install:A", ActivityKind.Install, "A", ActivityPhase.Running(done, 100),
            )
            val bmp = render(CelestiaStyle, true, Color(0xFF121212), "measure-$done") {
                Box(Modifier.height(44.dp)) { Pill(one, PillProps(), null, EnglishStrings) }
            }
            var hits = 0
            // Whole frame: at a low value the arc sits on the pill's lower edge,
            // and a scan window cut to one row height missed it entirely while
            // the drawing was correct.
            for (y in 0 until bmp.height) {
                for (x in 0 until width) {
                    if (near(bmp.getColor(x, y), accent.toArgbInt(), tolerance = 30)) hits++
                }
            }
            return hits
        }

        val low = accentPixels(10)
        val high = accentPixels(90)
        assertTrue(high > low, "more of the perimeter must be inked at 90% than at 10%: $low -> $high")
        assertTrue(low > 0, "the measure must draw something at 10%")
    }

    private fun Color.toArgbInt(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    private fun near(a: Int, b: Int, tolerance: Int): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) < tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) < tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) < tolerance

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
