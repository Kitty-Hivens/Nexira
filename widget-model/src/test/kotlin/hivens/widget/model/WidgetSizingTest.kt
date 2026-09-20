package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rules a widget's declared size is read by. Worth pinning because every one
 * of them has a "says nothing" case that has to behave exactly as the launcher
 * did before anything declared anything, and because the difference between a
 * claim and a limit is the bug this type exists to stop.
 */
class WidgetSizingTest {

    private val clock = WidgetSizing(
        minWidth = 80, minHeight = 92,
        prefWidth = 200, prefHeight = 230,
        maxWidth = 800, maxHeight = 920,
    )

    @Test
    fun `saying nothing is the default and reads as saying nothing`() {
        assertTrue(WidgetSizing().undeclared)
        assertFalse(clock.undeclared)
    }

    @Test
    fun `no claim is no bound, even where a ceiling was declared`() {
        // Substituting the ceiling here read as "never hold space you will not
        // paint", and cost more than it saved: a widget nobody has sized already
        // draws at its own size, and a ceiling that describes one configuration
        // then clipped the others. The disc's caption was the casualty.
        assertEquals(0f, clock.boundWidth(0f))
        assertEquals(0f, clock.boundHeight(-5f))
        assertEquals(0f, WidgetSizing(minWidth = 100).boundWidth(0f))
    }

    @Test
    fun `a claim under the floor is raised rather than allowed to cut`() {
        // The column player given 272 of the 304 it draws: the claim is honoured
        // as a maximum everywhere except where honouring it would remove content.
        assertEquals(80f, clock.boundWidth(40f))
        assertEquals(92f, clock.boundHeight(10f))
    }

    @Test
    fun `a claim over the ceiling reserves no space the widget will not paint`() {
        assertEquals(800f, clock.boundWidth(4000f))
        assertEquals(920f, clock.boundHeight(4000f))
    }

    @Test
    fun `a claim inside the range is the claim`() {
        assertEquals(300f, clock.boundWidth(300f))
        assertEquals(300f, clock.boundHeight(300f))
    }

    @Test
    fun `an undeclared axis passes a claim straight through`() {
        val widthOnly = WidgetSizing(minWidth = 100, maxWidth = 400)
        assertEquals(4000f, widthOnly.boundHeight(4000f), "nothing was said about height")
        assertEquals(400f, widthOnly.boundWidth(4000f))
    }

    @Test
    fun `a floor alone does not invent a ceiling`() {
        val floorOnly = WidgetSizing(minWidth = 120)
        assertEquals(120f, floorOnly.boundWidth(40f))
        assertEquals(9999f, floorOnly.boundWidth(9999f))
    }
}
