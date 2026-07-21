package hivens.ui.screens.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end proof that the canvas virtualizes: rendering a 5000-line buffer into a
 * small viewport must measure only ~a viewport of lines, not the whole buffer.
 * This is the render-path counterpart to ConsolePerfBench's isolated numbers.
 */
class LogCanvasRenderTest {

    private val palette = ConsolePalette(
        textPrimary = Color.White, textSecondary = Color.Gray,
        severityInfo = Color.White, severityWarn = Color.Yellow, severityError = Color.Red,
        divider = Color.DarkGray, searchMatch = Color.Black, searchMatchBg = Color.Yellow,
    )

    private fun bigBuffer(n: Int) = (0 until n).map { i ->
        val type = when (i % 20) { 7 -> LogType.WARN; 13 -> LogType.ERROR; else -> LogType.INFO }
        LogEntry("[Render thread/INFO] loaded chunk ($i) with entities and a fairly long tail t=$i", type, "12:00:00")
    }

    private fun renderAndCountMeasured(scrollTo: Float?): Long {
        val models = buildLineModels(
            all = bigBuffer(5000),
            filterInfo = true, filterWarn = true, filterError = true,
            searchAsFilter = false, rawQuery = "", regexMode = false, regexCompiled = null,
            showTimestamps = false,
        )
        var stateRef: LogCanvasState? = null
        val scene = ImageComposeScene(width = 900, height = 400, density = Density(1f)) {
            val state = rememberLogCanvasState().also { stateRef = it }
            if (scrollTo != null) state.scroll.scrollTo(scrollTo)
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                LogCanvas(
                    state = state,
                    lines = models,
                    palette = palette,
                    baseStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White),
                    wrap = false,
                    showGutter = true,
                    warnColor = Color.Yellow,
                    errorColor = Color.Red,
                    startPadPx = 10f,
                    topPadPx = 4f,
                    gutterWidthPx = 3f,
                )
            }
        }
        try {
            var f = 0L
            repeat(6) { scene.render(f); f += 16_000_000L }
        } finally {
            scene.close()
        }
        return stateRef!!.cache.measuredCount
    }

    @Test
    fun onlyMeasuresAViewportNotTheWholeBuffer() {
        val measured = renderAndCountMeasured(scrollTo = null)
        // 400px viewport at ~12sp mono => a few dozen lines. Must be nowhere near 5000.
        assertTrue(measured in 12..300, "expected ~a viewport of lines measured, got $measured of 5000")
    }

    @Test
    fun scrollingMeasuresOnlyTheNewlyVisibleLines() {
        // Jump into the middle; still only a bounded number of lines should measure.
        val measured = renderAndCountMeasured(scrollTo = 40_000f)
        assertTrue(measured in 12..300, "expected a bounded measure count after scroll, got $measured of 5000")
    }
}
