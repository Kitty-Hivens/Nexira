package hivens.ui.screens.console

import hivens.ui.utils.LogEntry
import hivens.ui.utils.LogType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The console's small decisions, which sat inline in a 1800-line composable
 * between a focus requester and a scroll offset, and so had never been
 * exercised: what a query compiles to, when history pages in, where a jump
 * lands, and what a copy produces.
 */
class ConsoleRulesTest {

    private fun line(text: String, ts: String = "12:00:00", type: LogType = LogType.INFO) =
        LogEntry(timestamp = ts, text = text, type = type)

    // --- search compilation ---

    @Test
    fun `plain text search compiles to no regex`() {
        assertNull(compileSearch("error", regexMode = false))
    }

    @Test
    fun `a valid pattern compiles and ignores case`() {
        val regex = compileSearch("err.r", regexMode = true)
        assertNotNull(regex)
        assertTrue(regex.containsMatchIn("ERROR"), "console search is case-insensitive")
    }

    @Test
    fun `a half-typed pattern is null rather than an exception`() {
        // Mid-keystroke state: the filter reads null as matching nothing, so the
        // user sees an empty buffer instead of a crash.
        assertNull(compileSearch("(unclosed", regexMode = true))
    }

    @Test
    fun `a blank query in regex mode compiles to nothing`() {
        assertNull(compileSearch("   ", regexMode = true))
    }

    // --- history paging ---

    @Test
    fun `history pages in at the top of a live buffer with older entries`() {
        assertTrue(shouldPageHistory(isLive = true, loading = false, historyOffset = 500, scrollOffsetPx = 12f))
    }

    @Test
    fun `a load already running is not re-entered`() {
        assertFalse(
            shouldPageHistory(isLive = true, loading = true, historyOffset = 500, scrollOffsetPx = 0f),
            "a fast scroll to the top would otherwise queue overlapping reads of the same batch",
        )
    }

    @Test
    fun `a file-backed view has nothing above it`() {
        assertFalse(shouldPageHistory(isLive = false, loading = false, historyOffset = 500, scrollOffsetPx = 0f))
    }

    @Test
    fun `nothing pages in with the window already whole`() {
        assertFalse(shouldPageHistory(isLive = true, loading = false, historyOffset = 0, scrollOffsetPx = 0f))
    }

    @Test
    fun `paging waits until the user is near the top`() {
        assertFalse(shouldPageHistory(isLive = true, loading = false, historyOffset = 500, scrollOffsetPx = 400f))
    }

    // --- match cursor ---

    @Test
    fun `the cursor wraps at both ends`() {
        assertEquals(1, nextMatchIndex(current = 0, total = 3))
        assertEquals(0, nextMatchIndex(current = 2, total = 3))
        assertEquals(2, previousMatchIndex(current = 0, total = 3))
        assertEquals(1, previousMatchIndex(current = 2, total = 3))
    }

    @Test
    fun `an empty match set has nowhere to jump`() {
        assertEquals(-1, nextMatchIndex(current = 0, total = 0))
        assertEquals(-1, previousMatchIndex(current = 0, total = 0))
    }

    @Test
    fun `a match set that shrank under the cursor resets it`() {
        assertEquals(0, clampMatchIndex(current = 7, total = 3))
        assertEquals(2, clampMatchIndex(current = 2, total = 3), "a cursor still in range stays put")
        assertEquals(0, clampMatchIndex(current = 3, total = 0))
    }

    // --- copy ---

    @Test
    fun `copying the buffer stamps every line but the dividers`() {
        val text = copyAllText(
            listOf(
                line("started", ts = "12:00:01"),
                line("---- session ----", type = LogType.DIVIDER),
                line("done", ts = "12:00:09"),
            ),
        )
        assertEquals("[12:00:01] started\n---- session ----\n[12:00:09] done", text)
    }

    // The buffer is passed as a size plus an accessor rather than a list: the
    // caller holds up to 50k line models, and materialising them to read one
    // line is a copy of the whole console per right-click.
    private fun copyLineFrom(lines: List<String>, caretLine: Int?): String? =
        copyLineText(lines.size, caretLine) { lines[it] }

    @Test
    fun `copying a line with no caret falls back to the first`() {
        assertEquals("first", copyLineFrom(listOf("first", "second"), caretLine = null))
    }

    @Test
    fun `a blank line is not worth copying`() {
        assertNull(copyLineFrom(listOf("   "), caretLine = 0))
    }

    @Test
    fun `a caret past the end copies nothing`() {
        assertNull(copyLineFrom(listOf("only"), caretLine = 9))
    }

    @Test
    fun `the accessor is never called for an out-of-range caret`() {
        var touched = false
        assertNull(copyLineText(lineCount = 2, caretLine = 5) { touched = true; "x" })
        assertFalse(touched, "an index check that reads first would throw on the buffer it is guarding")
    }

    @Test
    fun `an empty buffer copies nothing`() {
        assertNull(copyLineText(lineCount = 0, caretLine = null) { error("must not read an empty buffer") })
    }
}
