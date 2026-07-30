package hivens.ui.screens.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import hivens.ui.screens.ConsolePalette
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Off-screen visual capture of the log canvas -- the headless stand-in for "run it
 * live". Renders wrap + no-wrap, a flood append (sticky-bottom check), and a
 * selection, saving PNGs. @Ignore: run on demand.
 *   ./gradlew :client-ui:desktopTest --tests "hivens.ui.screens.console.ConsoleShots"
 */
@Ignore("visual capture harness; run on demand")
class ConsoleShots {

    private val outDir = File(System.getenv("CONSOLE_SHOTS_DIR") ?: "build/console-shots").apply { mkdirs() }

    private val palette = ConsolePalette(
        textPrimary = Color(0xFFE6E6E6), textSecondary = Color(0xFF9AA0AA),
        severityInfo = Color(0xFFE6E6E6), severityWarn = Color(0xFFE0B341), severityError = Color(0xFFE5726F),
        divider = Color(0xFF5A5A5A), searchMatch = Color(0xFF1A1A1A), searchMatchBg = Color(0xFFFFEB3B),
    )
    private val baseStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color(0xFFE6E6E6))

    // Long, numbered, mixed-severity lines -- long enough to wrap at the capture width.
    private fun line(i: Int): LogEntry {
        val type = when (i % 17) { 5 -> LogType.WARN; 11 -> LogType.ERROR; else -> LogType.INFO }
        val body = when (type) {
            LogType.ERROR -> "java.lang.IllegalStateException: chunk provider desynced while streaming region ($i, ${i * 3}); Caused by: stale token=$i in the persistence layer, retrying"
            LogType.WARN  -> "deprecated registry access from mod 'somemod' during entity tick; this path is scheduled for removal and will spam until the pack updates, at index $i"
            else          -> "[Render thread/INFO] loaded chunk ($i, ${i * 2}) with ${i % 512} block entities and a fairly long descriptive tail so the line wraps at this width, tick=$i"
        }
        return LogEntry("%04d | %s".format(i, body), type, "12:00:%02d".format(i % 60))
    }

    private fun buffer(n: Int) = (0 until n).map { line(it) }

    private fun models(entries: List<LogEntry>) = buildLineModels(
        all = entries, filterInfo = true, filterWarn = true, filterError = true,
        searchAsFilter = false, rawQuery = "", regexMode = false, regexCompiled = null,
        showTimestamps = true,
    )

    private fun save(scene: ImageComposeScene, name: String) {
        var f = 0L
        repeat(14) { scene.render(f); f += 16_000_000L }
        val png = scene.render(f).encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
        File(outDir, name).writeBytes(png.bytes)
    }

    @Test
    fun captures() {
        var entries by mutableStateOf(buffer(400))
        val selection = LogSelection()
        var stateRef: LogCanvasState? = null

        fun scene(wrap: Boolean) = ImageComposeScene(width = 960, height = 540, density = Density(1f)) {
            val st = rememberLogCanvasState().also { stateRef = it }
            val m = remember(entries) { models(entries) }
            Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                LogCanvas(
                    state = st, lines = m, selection = selection,
                    palette = palette, baseStyle = baseStyle, wrap = wrap, showGutter = true,
                    warnColor = Color(0xFFE0B341), errorColor = Color(0xFFE5726F),
                    selectionColor = Color(0x552F6BFF),
                    startPadPx = 12f, topPadPx = 6f, gutterWidthPx = 3f,
                    contentKey = "shots",
                )
            }
        }

        // 1) wrap ON, fresh view -> follows the tail: bottom shows line ~0399.
        val wrapScene = scene(wrap = true)
        save(wrapScene, "01-wrap-initial-following.png")

        // 2) flood: append 60 lines -> sticky-bottom must show the NEW tail (~0459),
        //    not stay parked at 0399.
        entries = entries + (400 until 460).map { line(it) }
        save(wrapScene, "02-wrap-after-append-sticky.png")

        // 3) selection across a few visible tail lines -> highlight band + copy model.
        val n = stateRef!!.let { entries.size }
        selection.select(DocPos((n - 6).coerceAtLeast(0), 7), DocPos((n - 3).coerceAtLeast(0), 22))
        save(wrapScene, "03-wrap-selection.png")
        // Sanity: copy assembles from the model.
        File(outDir, "03-selection-copied.txt").writeText(selection.copyText(models(entries)))

        // 4) no-wrap: long lines clipped at the right edge, gutter bars on WARN/ERROR.
        selection.collapse()
        val noWrapScene = scene(wrap = false)
        save(noWrapScene, "04-nowrap-initial.png")

        wrapScene.close(); noWrapScene.close()
        File(outDir, "INDEX.txt").writeText(
            """
            01-wrap-initial-following.png   wrap on, fresh view follows tail (bottom line ~0399)
            02-wrap-after-append-sticky.png after appending 60 lines, tail sticks (bottom ~0459)
            03-wrap-selection.png           selection highlight band across tail lines
            03-selection-copied.txt         text copyText() assembled from the model
            04-nowrap-initial.png           no-wrap: long lines clipped, gutter bars
            """.trimIndent(),
        )
    }
}
