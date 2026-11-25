package hivens.ui.chrome

import hivens.config.Branding
import hivens.ui.i18n.NexiraStrings
import hivens.widget.model.CompositionName
import hivens.widget.model.Frame
import hivens.widget.model.StandardDocument
import hivens.window.RESTORE_WINDOW_SIZE
import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** What a window makes of the frame its composition asks for. */
class FrameRequestTest {

    private val strings = NexiraStrings.shipped("en")

    @Test
    fun `a frame that says nothing leaves the window its own size and name`() {
        assertEquals(RESTORE_WINDOW_SIZE, Frame().size)
        assertNull(Frame().minimum)
        assertEquals(Branding.TITLE, Frame().titleIn(strings))
    }

    @Test
    fun `each axis is asked for apart`() {
        assertEquals(Dimension(500, RESTORE_WINDOW_SIZE.height), Frame(width = 500).size)
        assertEquals(Dimension(RESTORE_WINDOW_SIZE.width, 400), Frame(height = 400).size)
        assertEquals(Dimension(0, 300), Frame(minHeight = 300).minimum)
        assertNull(Frame(minWidth = -5, minHeight = -1).minimum, "a size below nothing asks for nothing")
        assertEquals(RESTORE_WINDOW_SIZE, Frame(width = -5, height = -1).size)
    }

    @Test
    fun `a title is the launcher's words for it, or as written`() {
        assertEquals(strings.text("consoleTitle"), Frame(title = "consoleTitle").titleIn(strings))
        assertNotEquals("consoleTitle", Frame(title = "consoleTitle").titleIn(strings))
        assertEquals("My own window", Frame(title = "My own window").titleIn(strings))
    }

    @Test
    fun `the standard document opens both windows the size they had in code`() {
        val document = StandardDocument.load()
        val launcher = document.startComposition!!.frame
        assertEquals(Dimension(1100, 720), launcher.size)
        assertEquals(Dimension(960, 600), launcher.minimum)
        val console = document.compositions.getValue(CompositionName("console")).frame
        assertEquals(Dimension(960, 620), console.size)
        assertEquals(strings.text("consoleTitle"), console.titleIn(strings))
    }
}
