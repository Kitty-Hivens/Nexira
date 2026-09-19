package hivens.ui.legacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.launcher.legacy.RetiredClient
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The leftover-clients surface, drawn so it can be looked at.
 *
 * Fixtures are the real install this was written for: seven folders, five
 * gigabytes, one of them two on its own because it ships a bundled JRE, and one
 * whose tree names no Minecraft version at all. That last row is the one the
 * layout has to survive -- it carries the longest sentence on the surface next
 * to two editable fields.
 */
class RetiredClientsRenderProbe {

    private fun client(name: String, size: Long, mc: String?, loader: String?, mods: Int) =
        RetiredClient(name, Path.of("/tmp/clients", name), size, mc, loader, mods)

    private val fixtures = listOf(
        client("Create", 1_986_000_000L, "1.21.1", "neoforge", 214),
        client("Galaxy", 556_000_000L, "1.12.2", "forge", 141),
        client("Industrial", 530_000_000L, "1.12.2", "forge", 168),
        client("Nevermine", 551_000_000L, "1.12.2", "forge", 96),
        client("RPG", 600_000_000L, "1.12.2", "forge", 120),
        client("SkyBlock", 460_000_000L, "1.7.10", "forge", 187),
        client("TechnoMagic", 605_000_000L, null, null, 203),
    )

    @Composable
    private fun Sheet(rows: List<RetiredRow>, finished: Boolean, reclaimed: Long) {
        Box(
            Modifier.fillMaxSize().background(NxTheme.colors.background).padding(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            RetiredClientsBody(
                rows = rows,
                loading = false,
                finished = finished,
                running = false,
                reclaimedBytes = reclaimed,
                onClose = {},
                onApply = {},
            )
        }
    }

    private fun draw(name: String, width: Int, height: Int, dark: Boolean, content: @Composable () -> Unit) {
        val out = Path.of("build/render", "retired-$name.png")
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = dark) {
                LocaleProvider(AppLocale.RUSSIAN) { content() }
            }
        }
        try {
            var t = 0L
            repeat(4) { t += 16_000_000L; scene.render(t) }
            val png = scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            Files.write(out, png.bytes)
        } finally {
            scene.close()
        }
        println("probe written: ${out.toAbsolutePath()}")
    }

    @Test
    fun `the chooser as a real install presents it`() {
        val rows = fixtures.map { RetiredRow(it) }
        rows[0].choice = RetiredChoice.Adopt
        rows[1].choice = RetiredChoice.Delete
        draw("chooser-dark", 1000, 900, dark = true) { Sheet(rows, finished = false, reclaimed = 0L) }
        draw("chooser-light", 1000, 900, dark = false) { Sheet(rows, finished = false, reclaimed = 0L) }
    }

    /** The narrowest window the shell allows, where the row has least room. */
    @Test
    fun `the chooser at the minimum window width`() {
        val rows = fixtures.map { RetiredRow(it) }
        draw("chooser-narrow", 700, 900, dark = true) { Sheet(rows, finished = false, reclaimed = 0L) }
    }

    @Test
    fun `the outcome after a pass`() {
        val rows = fixtures.map { RetiredRow(it) }
        rows[0].outcome = RetiredOutcome.Adopted("Create", sourceKept = false)
        rows[1].outcome = RetiredOutcome.Deleted
        rows[2].outcome = RetiredOutcome.Adopted("Industrial", sourceKept = true)
        rows[3].outcome = RetiredOutcome.Failed("remove")
        draw("done-dark", 1000, 700, dark = true) { Sheet(rows, finished = true, reclaimed = 3_100_000_000L) }
    }
}
