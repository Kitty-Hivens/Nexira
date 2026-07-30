package hivens.ui.screens.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import hivens.ui.screens.ConsolePalette
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LineLayoutCacheTest {

    private fun measurer() = TextMeasurer(
        defaultFontFamilyResolver = createFontFamilyResolver(),
        defaultDensity = Density(1f),
        defaultLayoutDirection = LayoutDirection.Ltr,
    )

    private val style = LineStyle(
        palette = ConsolePalette(
            textPrimary = Color.White, textSecondary = Color.Gray,
            severityInfo = Color.White, severityWarn = Color.Yellow, severityError = Color.Red,
            divider = Color.DarkGray, searchMatch = Color.Black, searchMatchBg = Color.Yellow,
        ),
        baseStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        wrap = false,
        widthPx = 0,
    )

    private fun line(text: String, type: LogType = LogType.INFO): LineModel =
        buildLineModels(
            all = listOf(LogEntry(text, type, "12:00:00")),
            filterInfo = true, filterWarn = true, filterError = true,
            searchAsFilter = false, rawQuery = "", regexMode = false, regexCompiled = null,
            showTimestamps = false,
        ).lines.single()

    @Test
    fun measuresOnceThenServesFromCache() {
        val cache = LineLayoutCache(measurer())
        val l = line("hello world")
        val a = cache.layoutFor(l, style)
        val b = cache.layoutFor(l, style)
        assertEquals(1L, cache.measuredCount)
        assertSame(a, b)
    }

    @Test
    fun distinctLinesMeasureSeparately() {
        val cache = LineLayoutCache(measurer())
        cache.layoutFor(line("alpha"), style)
        cache.layoutFor(line("beta"), style)
        cache.layoutFor(line("alpha"), style) // same key as first -> hit
        assertEquals(2L, cache.measuredCount)
    }

    @Test
    fun invalidateForcesRemeasure() {
        val cache = LineLayoutCache(measurer())
        val l = line("gamma")
        cache.layoutFor(l, style)
        cache.invalidate()
        cache.layoutFor(l, style)
        assertEquals(2L, cache.measuredCount)
    }

    @Test
    fun lruEvictsBeyondCapacity() {
        val cache = LineLayoutCache(measurer(), capacity = 2)
        val a = line("one"); val b = line("two"); val c = line("three")
        cache.layoutFor(a, style) // [a]
        cache.layoutFor(b, style) // [a,b]
        cache.layoutFor(c, style) // evicts a -> [b,c]
        assertEquals(3L, cache.measuredCount)
        cache.layoutFor(a, style) // a was evicted -> re-measure
        assertEquals(4L, cache.measuredCount)
    }
}
