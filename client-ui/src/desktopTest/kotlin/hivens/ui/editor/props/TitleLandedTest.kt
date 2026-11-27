package hivens.ui.editor.props

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A screen's title field keeps what is typed while its own writes land, and shows a title changed under it. */
class TitleLandedTest {

    @Test
    fun `the field's own writes landing one by one leave what it shows alone`() {
        val pending = ArrayDeque(listOf("N", "No", "Not"))
        assertNull(titleLanded(pending, "N", "Not"), "a frame behind the typing, not a change")
        assertNull(titleLanded(pending, "Not", "Not"))
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `a write skipped on the way still counts as the field's own`() {
        val pending = ArrayDeque(listOf("N", "No", "Not"))
        assertNull(titleLanded(pending, "No", "Not"))
        assertEquals(listOf("Not"), pending.toList())
    }

    @Test
    fun `an undo is a title the field never wrote, and it is shown`() {
        val pending = ArrayDeque<String>()
        assertEquals("Screen 1", titleLanded(pending, "Screen 1", "Notes"))
        assertNull(titleLanded(pending, "Notes", "Notes"), "and the same title as shown is nothing to change")
    }
}
