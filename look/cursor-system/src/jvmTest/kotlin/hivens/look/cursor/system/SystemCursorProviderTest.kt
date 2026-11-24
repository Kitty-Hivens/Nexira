package hivens.look.cursor.system

import androidx.compose.ui.input.pointer.PointerIcon
import hivens.look.cursor.api.Cursor
import hivens.look.cursor.api.CursorAnswers
import hivens.look.cursor.api.CursorApi
import hivens.look.cursor.api.CursorEdge
import hivens.look.cursor.api.CursorRole
import hivens.widget.model.ApiVersion
import java.awt.Cursor as AwtCursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The system provider answers the cursor axis with what the system has, and leaves alone what it has not. */
class SystemCursorProviderTest {

    private val asked = ArrayList<String>()
    private val grab = PointerIcon(AwtCursor(AwtCursor.HAND_CURSOR))
    private val grabbing = PointerIcon(AwtCursor(AwtCursor.MOVE_CURSOR))

    private fun desktop(vararg theme: Pair<String, PointerIcon>) = DesktopPointers { name ->
        asked += name
        theme.toMap()[name]
    }

    @Test
    fun `it answers the cursor axis as the system family, offers itself by its id, and is named in every language the module ships`() {
        assertEquals(Cursor.id, SystemCursorProvider.axis)
        assertEquals("system", SystemCursorProvider.family)
        assertEquals(CursorApi.VERSION, SystemCursorProvider.contract)
        assertNull(SystemCursorProvider.basedOn)
        assertEquals(ApiVersion(1, 0), CursorSystemModule.version)
        assertEquals(listOf<Any>(SystemCursorProvider), CursorSystemModule.contributions)
        assertTrue(CursorSystemModule.strings.tables.values.all { SystemCursorProvider.label in it && CursorSystemModule.name in it })
    }

    @Test
    fun `what is pressed is under the arrow, usable or not`() {
        val pointers = desktop()
        assertSame(PointerIcon.Default, pointers.pointer(CursorRole.Pressable))
        assertSame(PointerIcon.Default, pointers.pointer(CursorRole.Disabled))
        assertSame(PointerIcon.Hand, pointers.pointer(CursorRole.Link))
        assertSame(PointerIcon.Text, pointers.pointer(CursorRole.Text))
        assertEquals(emptyList(), asked, "the toolkit has these, and the theme is not asked")
    }

    @Test
    fun `the hands and the refusal are the theme's, asked once each by the names themes have them under`() {
        val refused = PointerIcon(AwtCursor(AwtCursor.WAIT_CURSOR))
        val pointers = desktop("grab" to grab, "grabbing" to grabbing, "not-allowed" to refused)
        repeat(3) {
            assertSame(grab, pointers.pointer(CursorRole.Draggable))
            assertSame(grabbing, pointers.pointer(CursorRole.Dragging))
            assertSame(refused, pointers.pointer(CursorRole.Refused))
        }
        assertEquals(listOf("grab", "grabbing", "not-allowed"), asked)
    }

    @Test
    fun `where the system has no such pointer the role is left alone`() {
        listOf(CursorRole.Draggable, CursorRole.Dragging, CursorRole.Refused).forEach { assertNull(desktop().pointer(it), "$it") }
        assertNull(desktop("grabbing" to grabbing).pointer(CursorRole.Dragging), "one hand alone would change the pointer halfway through a drag")
    }

    @Test
    fun `an edge has the platform's arrow`() {
        CursorEdge.entries.forEach { assertSame(CursorAnswers.BASE.resize(it), SystemCursorProvider.ANSWERS.resize(it)) }
    }
}
