package hivens.ui.screens.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewLinesTest {

    @Test
    fun `a row per line`() {
        assertEquals(listOf("a", "", "b"), previewLines("a\n\nb"))
        assertEquals(listOf("a", "b"), previewLines("a\r\nb"))
    }

    @Test
    fun `a minified file is not one row`() {
        val line = "x".repeat(10_000)
        val rows = previewLines(line)

        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.length <= 4096 })
        assertEquals(line, rows.joinToString(""))
    }
}
