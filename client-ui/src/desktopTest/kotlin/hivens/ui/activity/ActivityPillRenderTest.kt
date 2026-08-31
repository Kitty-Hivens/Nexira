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
 * The scene has to be big enough for what it composes. An undersized one clamps
 * the pill's height through the parent constraint and then everything downstream
 * -- a control spilling past the body, a perimeter that looks detached from it --
 * reads as a layout defect in the widget rather than in the harness.
 *
 * Sheet of the pill's states across both styles and both palettes, plus the two
 * measurements worth pinning: the body separates from whatever it floats over,
 * and the perimeter measure actually advances with the value.
 *
 * The widget itself pulls its subject from the registry through Koin, so what is
 * exercised here is the presentation -- the same [Pill] the widget composes,
 * handed activities directly. A PNG lands under build/render for eyeballing.
 */
class ActivityPillRenderTest {

    private val width = 1180
    private val rowHeight = 176

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
    private val sync = activity(
        "sync:Industrial", ActivityKind.Sync, "Industrial", ActivityPhase.Running(0, 0),
    )
    private val repair = activity(
        "repair:SkyBlock", ActivityKind.Repair, "SkyBlock", ActivityPhase.Running(2, 9),
    )
    private val failed = activity(
        "install:Create", ActivityKind.Install, "Create", ActivityPhase.Failed("timeout"),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    /** Accent captured out of the real theme, so the probe cannot drift from it. */
    private var accent: Color = Color.Unspecified

    /**
     * Rendered at the density the app actually runs at, not at a comfortable one.
     * A sheet drawn at 2x on a display that runs at 1x makes every proportion
     * judgement wrong by a factor of two, and the judgements are the whole reason
     * the sheet exists.
     */
    private fun render(
        style: StyleSpec,
        dark: Boolean,
        name: String,
        scale: Float = 1f,
        content: @Composable () -> Unit,
    ): Bitmap {
        val scene = ImageComposeScene(
            width = (width * scale).toInt(),
            height = (rowHeight * 3 * scale).toInt(),
            density = Density(scale),
        ) {
            // The real theme entry point rather than a hand-provided palette: what
            // the sheet shows is then what the app resolves, tonal ladder included.
            NxTheme(useDarkTheme = dark, style = style) {
                CompositionLocalProvider(
                    LocalStyle provides style,
                    LocalStrings provides EnglishStrings,
                ) {
                    accent = NxTheme.colors.progressAccent
                    Column(
                        modifier = Modifier.fillMaxSize().background(NxTheme.colors.background).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) { content() }
                }
            }
        }
        val image = scene.render()
        scene.close()
        val out = File("build/render").apply { mkdirs() }
        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(out, "activity-pill-$name@${scale.toInt()}x.png").writeBytes(it) }
        return Bitmap.makeFromImage(image)
    }

    private val selection = Selection(
        items = listOf(
            SelectionItem("mods:sodium.jar", "Sodium"),
            SelectionItem("mods:iris.jar", "Iris"),
            SelectionItem("mods:jei.jar", "JEI"),
            SelectionItem("mods:rei.jar", "REI"),
        ),
        actions = listOf(
            SelectionAction(SelectionActionKind.Enable) {},
            SelectionAction(SelectionActionKind.Disable, blockedReason = "3 of these belong to the pack.") {},
            SelectionAction(SelectionActionKind.Delete, blockedReason = "3 of these belong to the pack.") {},
        ),
        clear = {},
    )

    @Composable
    private fun Sheet(props: PillProps) {
        val commands: ActivityCommands? = null // static sheet: nothing to click
        Box { Pill(running, listOf(running, failed, sync, repair), {}, props, commands, EnglishStrings, 720.dp) }
        Box { Pill(failed, listOf(failed), {}, props, commands, EnglishStrings, 720.dp) }
        // Selection holds the body while the launcher's own work stays at the lead.
        Box { SelectionPill(selection, running, props, EnglishStrings, 720.dp) }
    }

    @Test
    fun `the body separates from the ground it floats over`() {
        // The pill's whole premise is that it reads over arbitrary content, so
        // the one thing that must hold in every combination is that its body is
        // not the same tone as what is behind it. A near-black ground is the
        // hardest case -- it is the default with no wallpaper.
        val cases = listOf(
            "celestia-dark" to (CelestiaStyle to true),
            "celestia-light" to (CelestiaStyle to false),
        )
        for ((name, styling) in cases) {
            val (style, dark) = styling
            // Both, so a proportion can be judged at the scale it will be seen at.
            render(style, dark, name, scale = 2f) { Sheet(PillProps()) }
            val bmp = render(style, dark, name) { Sheet(PillProps()) }
            // Sampled as a fraction of the frame, not at pixels that happened to
            // work at one density. The whole point of rendering at the app's own
            // scale is lost if the probe still assumes a different one.
            val body = bmp.getColor(bmp.width / 3, bmp.height / 8)
            val bg = bmp.getColor(bmp.width - 4, bmp.height - 4)
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
            val bmp = render(CelestiaStyle, true, "measure-$done") {
                Box { Pill(one, listOf(one), {}, PillProps(), null, EnglishStrings, 720.dp) }
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
