package hivens.ui.screens.console

import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogSelectionTest {

    private fun models(vararg texts: String) = buildLineModels(
        all = texts.map { LogEntry(it, LogType.INFO, "12:00:00") },
        filterInfo = true, filterWarn = true, filterError = true,
        searchAsFilter = false, rawQuery = "", regexMode = false, regexCompiled = null,
        showTimestamps = false,
    )

    @Test
    fun caretIsNotAnActiveSelection() {
        val sel = LogSelection()
        sel.setCaret(DocPos(1, 3))
        assertFalse(sel.active)
        assertEquals("", sel.copyText(models("a", "bbbb")))
    }

    @Test
    fun normalizesBackwardSelection() {
        val sel = LogSelection()
        sel.beginAt(DocPos(2, 5))
        sel.extendTo(DocPos(0, 1)) // dragged upward
        assertEquals(DocPos(0, 1), sel.start)
        assertEquals(DocPos(2, 5), sel.end)
        assertTrue(sel.active)
    }

    @Test
    fun rangeOnLineClipsToEachLine() {
        val sel = LogSelection()
        sel.select(DocPos(0, 2), DocPos(2, 3))
        // first line: from offset 2 to its end
        assertEquals(2 until 5, sel.rangeOnLine(0, 5))
        // middle line fully covered
        assertEquals(0 until 8, sel.rangeOnLine(1, 8))
        // last line: from start to offset 3
        assertEquals(0 until 3, sel.rangeOnLine(2, 10))
        // outside the selection
        assertNull(sel.rangeOnLine(3, 4))
    }

    @Test
    fun copyAssemblesAcrossLinesFromModel() {
        val m = models("hello", "world", "again")
        val sel = LogSelection()
        sel.select(DocPos(0, 2), DocPos(2, 2)) // "llo" + "world" + "ag"
        assertEquals("llo\nworld\nag", sel.copyText(m))
    }

    @Test
    fun selectAllSpansWholeBuffer() {
        val m = models("one", "twotwo")
        val sel = LogSelection()
        sel.selectAll(m)
        assertEquals(DocPos(0, 0), sel.start)
        assertEquals(DocPos(1, 6), sel.end)
        assertEquals("one\ntwotwo", sel.copyText(m))
    }
}
