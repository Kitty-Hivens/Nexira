package hivens.window

import java.awt.Dimension
import java.awt.Rectangle
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the window opens as, from what was saved and the screens there are now. */
class WindowSessionTest {

    // A 1920x1080 monitor with a 48 px taskbar at the bottom, and a second one to its right.
    private val left = Rectangle(0, 0, 1920, 1032)
    private val right = Rectangle(1920, 0, 2560, 1400)
    private val restore = Dimension(1100, 720)
    private val floor = Dimension(800, 600)

    private fun open(saved: SavedWindow?, areas: List<Rectangle> = listOf(left, right), placeable: Boolean = true) =
        openingFrame(saved, areas, areas.first(), restore, floor, placeable)

    @Test
    fun `a first run opens maximised over the primary work area`() {
        val frame = open(null)
        assertTrue(frame.maximized)
        assertEquals(left, frame.bounds)
        assertNull(frame.normal, "nothing chosen yet, so the first restore picks its own size")
    }

    @Test
    fun `a window left as a window opens where it was left`() {
        val frame = open(SavedWindow(maximized = false, x = 200, y = 100, width = 1200, height = 800))
        assertEquals(false, frame.maximized)
        assertEquals(Rectangle(200, 100, 1200, 800), frame.bounds)
        assertEquals(frame.bounds, frame.normal)
    }

    @Test
    fun `a window left maximised opens maximised on the screen it was on, and restores to its frame`() {
        val frame = open(SavedWindow(maximized = true, x = 2100, y = 200, width = 1200, height = 800))
        assertTrue(frame.maximized)
        assertEquals(right, frame.bounds)
        assertEquals(Rectangle(2100, 200, 1200, 800), frame.normal)
    }

    @Test
    fun `a window on a monitor that is gone comes back to the primary one`() {
        val frame = open(SavedWindow(maximized = false, x = 2100, y = 200, width = 1200, height = 800), areas = listOf(left))
        assertEquals(restoredBounds(left, restore), frame.bounds)
    }

    @Test
    fun `a window bigger than its screen is shrunk to it`() {
        val frame = open(SavedWindow(maximized = false, x = 0, y = 0, width = 3000, height = 2000), areas = listOf(left))
        assertEquals(Rectangle(0, 0, 1920, 1032), frame.bounds)
    }

    @Test
    fun `a window partly off screen stays where it is while its top can be grabbed`() {
        assertEquals(Rectangle(1700, 900, 800, 600), fitToScreens(Rectangle(1700, 900, 800, 600), listOf(left)))
    }

    @Test
    fun `a window with only a sliver of its top on screen is not kept`() {
        assertNull(fitToScreens(Rectangle(1880, 1020, 800, 600), listOf(left)))
    }

    @Test
    fun `a window slightly above the top of the screen is brought down to it`() {
        assertEquals(Rectangle(100, 0, 800, 600), fitToScreens(Rectangle(100, -10, 800, 600), listOf(left)))
    }

    @Test
    fun `a window whose top is off every screen cannot be grabbed and is not kept`() {
        assertNull(fitToScreens(Rectangle(100, -400, 800, 600), listOf(left)))
    }

    @Test
    fun `a window manager that places windows itself gets what it always got`() {
        val frame = open(SavedWindow(maximized = false, x = 200, y = 100, width = 1200, height = 800), placeable = false)
        assertTrue(frame.maximized)
        assertEquals(left, frame.bounds)
    }

    @Test
    fun `the record survives the file it is kept in, and a bad file reads as nothing`() {
        val dir = Files.createTempDirectory("window")
        val saved = SavedWindow(maximized = true, x = -10, y = 20, width = 1300, height = 900)
        SavedWindow.write(dir, saved)
        assertEquals(saved, SavedWindow.read(dir))

        dir.resolve(SavedWindow.FILE).writeText("{not json")
        assertNull(SavedWindow.read(dir))
        dir.resolve(SavedWindow.FILE).writeText("""{"maximized":false,"x":0,"y":0,"width":0,"height":0}""")
        assertNull(SavedWindow.read(dir), "a frame of no size is not a frame")
        assertNull(SavedWindow.read(Files.createTempDirectory("empty")))
    }
}
