package hivens.ui.screens.console

import hivens.ui.screens.SpanRole
import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LineModelTest {

    private fun entry(text: String, type: LogType) = LogEntry(text, type, timestamp = "12:00:00")

    private val sample = listOf(
        entry("starting up", LogType.INFO),
        entry("a warning here", LogType.WARN),
        entry("NullPointerException boom", LogType.ERROR),
    )

    private fun build(
        all: List<LogEntry> = sample,
        filterInfo: Boolean = true,
        filterWarn: Boolean = true,
        filterError: Boolean = true,
        searchAsFilter: Boolean = false,
        rawQuery: String = "",
        showTimestamps: Boolean = false,
    ) = buildLineModels(
        all = all,
        filterInfo = filterInfo, filterWarn = filterWarn, filterError = filterError,
        searchAsFilter = searchAsFilter, rawQuery = rawQuery,
        regexMode = false, regexCompiled = null, showTimestamps = showTimestamps,
    )

    @Test
    fun countsCoverAllEntriesEvenWhenSeverityFiltered() {
        val m = build(filterWarn = false, filterError = false)
        assertEquals(1, m.warnCount)
        assertEquals(1, m.errorCount)
        assertEquals(3, m.totalCount)
        assertEquals(1, m.filteredCount)
        assertEquals(1, m.lines.size)
        assertEquals("starting up", m.lines[0].text)
    }

    @Test
    fun timestampPrefixIsLineLocal() {
        val m = build(all = listOf(entry("hi", LogType.INFO)), showTimestamps = true)
        assertEquals("[12:00:00] hi", m.lines[0].text)
        // Base span covers the whole line, offsets local to the line.
        val base = m.lines[0].spans.first()
        assertEquals(0, base.start)
        assertEquals("[12:00:00] hi".length, base.end)
    }

    @Test
    fun searchAsFilterNarrowsAndRecordsLineLocalMatches() {
        val m = build(searchAsFilter = true, rawQuery = "warning")
        assertEquals(1, m.filteredCount)
        assertEquals("a warning here", m.lines[0].text)
        assertEquals(1, m.matches.size)
        val hit = m.matches[0]
        assertEquals(0, hit.line)
        assertEquals("warning", m.lines[0].text.substring(hit.start, hit.end))
        // A Search span exists on the line at the same local offsets.
        assertTrue(m.lines[0].spans.any { it.role == SpanRole.Search && it.start == hit.start && it.end == hit.end })
    }

    @Test
    fun searchHighlightsWithoutFilteringKeepsAllLines() {
        val m = build(rawQuery = "boom") // highlight only
        assertEquals(3, m.filteredCount)
        assertEquals(1, m.matches.size)
        assertEquals(2, m.matches[0].line) // the ERROR line, in filtered space
    }

    @Test
    fun errorMarkersAreLineLocalOverlays() {
        val m = build(all = listOf(entry("NullPointerException at Foo.bar", LogType.ERROR)))
        val line = m.lines[0]
        val markers = line.spans.filter { it.role == SpanRole.Marker }
        assertTrue(markers.isNotEmpty(), "expected exception markers on an ERROR line")
        // Every marker sits within the line's own text bounds.
        assertTrue(markers.all { it.start >= 0 && it.end <= line.text.length && it.start < it.end })
    }

    @Test
    fun dividersAlwaysPassAndCarryOneSpan() {
        val m = build(
            all = listOf(entry("=== session ===", LogType.DIVIDER)),
            filterInfo = false, filterWarn = false, filterError = false,
        )
        assertEquals(1, m.filteredCount)
        assertEquals(SpanRole.Divider, m.lines[0].spans.single().role)
    }
}
