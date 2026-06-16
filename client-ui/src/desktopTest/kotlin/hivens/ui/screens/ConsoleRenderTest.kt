package hivens.ui.screens

import androidx.compose.ui.graphics.Color
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleRenderTest {

    private fun entry(text: String, type: LogType) = LogEntry(text, type, timestamp = "12:00:00")

    private fun palette(seed: Color) = ConsolePalette(
        textPrimary   = seed,
        textSecondary = seed,
        severityInfo  = seed,
        severityWarn  = seed,
        severityError = seed,
        divider       = seed,
        searchMatch   = seed,
        searchMatchBg = seed,
    )

    private val sample = listOf(
        entry("starting up", LogType.INFO),
        entry("a warning here", LogType.WARN),
        entry("NullPointerException boom", LogType.ERROR),
    )

    private fun doc(
        all: List<LogEntry> = sample,
        filterInfo: Boolean = true,
        filterWarn: Boolean = true,
        filterError: Boolean = true,
        searchAsFilter: Boolean = false,
        rawQuery: String = "",
        showTimestamps: Boolean = false,
    ): ConsoleDoc = buildConsoleDoc(
        all = all,
        filterInfo = filterInfo,
        filterWarn = filterWarn,
        filterError = filterError,
        searchAsFilter = searchAsFilter,
        rawQuery = rawQuery,
        regexMode = false,
        regexCompiled = null,
        showTimestamps = showTimestamps,
    )

    @Test
    fun countsCoverAllEntriesEvenWhenSeverityFiltered() {
        val d = doc(filterWarn = false, filterError = false)
        // warn/error counts are over ALL entries, not just the shown ones
        assertEquals(1, d.warnCount)
        assertEquals(1, d.errorCount)
        assertEquals(3, d.totalCount)
        // only the INFO line passed the severity gate
        assertEquals(1, d.filteredCount)
        assertEquals("starting up", d.text)
    }

    @Test
    fun timestampPrefixIsAppliedInTheStructuralPass() {
        val d = doc(all = listOf(entry("hi", LogType.INFO)), showTimestamps = true)
        assertEquals("[12:00:00] hi", d.text)
    }

    @Test
    fun searchAsFilterNarrowsToMatchingLinesAndRecordsRanges() {
        val d = doc(searchAsFilter = true, rawQuery = "warning")
        assertEquals("a warning here", d.text)
        assertEquals(1, d.filteredCount)
        assertEquals(1, d.ranges.size)
        assertEquals("warning", d.text.substring(d.ranges[0].first, d.ranges[0].last + 1))
    }

    @Test
    fun stylingPreservesStructureAndOnlyChangesColoursAcrossPalettes() {
        val d = doc(rawQuery = "boom") // highlight only, no filtering
        val red  = styleDoc(d, palette(Color.Red))
        val blue = styleDoc(d, palette(Color.Blue))

        // Structure is identical: text, span count, ranges, counts all match the
        // palette-free document and each other -- a restyle is all a theme change
        // costs.
        assertEquals(d.text, red.annotated.text)
        assertEquals(red.annotated.text, blue.annotated.text)
        assertEquals(d.spans.size, red.annotated.spanStyles.size)
        assertEquals(red.annotated.spanStyles.size, blue.annotated.spanStyles.size)
        assertEquals(red.ranges, blue.ranges)
        assertEquals(red.warnCount, blue.warnCount)
        assertEquals(red.errorCount, blue.errorCount)

        // ...but the colours differ.
        assertTrue(red.annotated.spanStyles.isNotEmpty())
        assertTrue(
            red.annotated.spanStyles.zip(blue.annotated.spanStyles)
                .any { (r, b) -> r.item.color != b.item.color },
            "expected at least one span to recolour between palettes",
        )
    }
}
