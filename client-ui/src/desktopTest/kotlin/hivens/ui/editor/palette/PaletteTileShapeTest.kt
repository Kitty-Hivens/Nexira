package hivens.ui.editor.palette

import hivens.widget.model.WidgetSizing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shape of a gallery tile, and the one thing it must not depend on.
 *
 * A tile used to take its proportions from its preview bitmap. Every tile
 * therefore held a guessed shape until its render finished and then changed
 * height, one at a time, in whatever order the renders came back, under a reader
 * who was scrolling. What is pinned here is that the shape is a function of the
 * declaration alone, which is known on the first frame and never changes after.
 */
class PaletteTileShapeTest {

    private val clock = WidgetSizing(
        minWidth = 80, minHeight = 92,
        prefWidth = 200, prefHeight = 230,
        maxWidth = 800, maxHeight = 920,
    )

    @Test
    fun `a declared widget's tile takes its declared proportions`() {
        assertEquals(200f / 230f, thumbRatio(clock), 0.0001f)
    }

    @Test
    fun `the shape is the frame the preview is drawn into`() {
        // The two have to agree or the picture arrives letterboxed inside a tile
        // shaped for something else, which is the look the aspect was for.
        listOf(clock, WidgetSizing(), WidgetSizing(prefWidth = 240, prefHeight = 220)).forEach { sizing ->
            val frame = frameFor(sizing)
            assertEquals(frame.width / frame.height, thumbRatio(sizing), 0.0001f, "for $sizing")
        }
    }

    @Test
    fun `a widget that declares nothing still gets a shape`() {
        // Stable rather than right: there is no declaration to be right about,
        // and a tile that resizes later is worse than one that letterboxes.
        val undeclared = thumbRatio(WidgetSizing())
        assertTrue(undeclared > 0f)
        assertEquals(undeclared, thumbRatio(WidgetSizing()), "the same widget has to get the same tile twice")
    }

    @Test
    fun `one very tall widget cannot own the panel`() {
        val tall = WidgetSizing(prefWidth = 100, prefHeight = 2000)
        assertTrue(thumbRatio(tall) >= 0.85f, "a tile 20 times its width in height is a panel, not a tile")
    }

    @Test
    fun `one very long widget cannot flatten the row it shares`() {
        val long = WidgetSizing(prefWidth = 2000, prefHeight = 48)
        assertTrue(thumbRatio(long) <= 2.6f, "a hairline tile says nothing about the widget in it")
    }

    @Test
    fun `an axis declared alone does not produce a degenerate tile`() {
        // Half a declaration is a real state: a record player declares a width
        // range and one preferred height, and nothing on the other axis.
        listOf(
            WidgetSizing(prefWidth = 168),
            WidgetSizing(prefHeight = 304),
            WidgetSizing(minWidth = 88, maxWidth = 320, prefHeight = 304),
        ).forEach { sizing ->
            val ratio = thumbRatio(sizing)
            assertTrue(ratio.isFinite() && ratio > 0f, "$sizing gave $ratio")
        }
    }
}
