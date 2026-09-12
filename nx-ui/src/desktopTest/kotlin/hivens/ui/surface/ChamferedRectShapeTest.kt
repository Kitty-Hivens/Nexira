package hivens.ui.surface

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asSkiaPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shape's contract, asserted rather than looked at.
 *
 * Every claim here is one an eye cannot settle at the sizes this form is used at:
 * a cut of twelve points is a couple of dozen pixels, and whether it stayed the
 * same when the box grew is exactly the class of thing a rendered sheet hides.
 */
class ChamferedRectShapeTest {

    private val density = Density(2f)

    private fun outline(shape: ChamferedRectShape, w: Float, h: Float) =
        (shape.createOutline(Size(w, h), LayoutDirection.Ltr, density) as Outline.Generic).path.asSkiaPath()

    /**
     * The curve's own extent, not `Path.bounds`.
     *
     * `bounds` is the box around the CONTROL points, so a rounded apex reports an
     * extent the ink never reaches, and asymmetrically at that: the same shape read
     * through it looked 3.37 short on one end and 2.65 on the other, which is an
     * artefact of where the cubics were split rather than a defect of the outline.
     */
    private fun tight(shape: ChamferedRectShape, w: Float, h: Float) = outline(shape, w, h).computeTightBounds()

    @Test
    fun `no cut is a plain rectangle that fills its box`() {
        val path = outline(ChamferedRectShape(startCutDp = 0f, endCutDp = 0f), 200f, 40f)
        val b = path.computeTightBounds()
        assertEquals(0f, b.left, 0.5f)
        assertEquals(0f, b.top, 0.5f)
        assertEquals(200f, b.right, 0.5f)
        assertEquals(40f, b.bottom, 0.5f)
        // Every corner is real corner, not cut away.
        for ((x, y) in listOf(2f to 2f, 198f to 2f, 198f to 38f, 2f to 38f)) {
            assertTrue(path.contains(x, y), "corner ($x, $y) must be inside an uncut rectangle")
        }
    }

    @Test
    fun `the cut is a length, so it does not change when the box grows`() {
        // 12dp at density 2 is 24px of point, whatever the box is.
        val shape = ChamferedRectShape(startCutDp = 12f, endCutDp = 12f)
        for (width in listOf(160f, 480f, 1200f)) {
            val path = outline(shape, width, 40f)
            // Near the top edge the boundary sits at the cut depth: a quarter of
            // the way in is outside the point, twice the depth is well inside.
            assertFalse(path.contains(6f, 1f), "width $width: 6px in at the top edge is inside the cut away corner")
            assertTrue(path.contains(48f, 1f), "width $width: 48px in at the top edge must be inside the body")
            // And the same on the far end, which is what makes it a hexagon
            // rather than a tag.
            assertFalse(path.contains(width - 6f, 1f), "width $width: the far point is not cut")
            assertTrue(path.contains(width - 48f, 1f), "width $width: the far body is missing")
        }
    }

    @Test
    fun `one cut points one way and leaves the other end square`() {
        val path = outline(ChamferedRectShape(startCutDp = 12f, endCutDp = 0f), 200f, 40f)
        assertFalse(path.contains(6f, 1f), "the cut end must be cut")
        assertTrue(path.contains(198f, 1f), "the square end must keep its corner")
    }

    @Test
    fun `equal cuts are symmetric about the middle`() {
        val w = 300f
        val path = outline(ChamferedRectShape(startCutDp = 10f, endCutDp = 10f), w, 48f)
        for (x in listOf(4f, 12f, 19f, 26f, 40f)) {
            for (y in listOf(1f, 8f, 24f, 40f, 47f)) {
                assertEquals(
                    path.contains(x, y),
                    path.contains(w - x, y),
                    "($x, $y) and its mirror must agree",
                )
            }
        }
    }

    @Test
    fun `a cut past half the axis collapses to a rhombus instead of inverting`() {
        val path = outline(ChamferedRectShape(startCutDp = 400f, endCutDp = 400f), 200f, 40f)
        val b = path.computeTightBounds()
        assertEquals(200f, b.right - b.left, 0.5f)
        assertEquals(40f, b.bottom - b.top, 0.5f)
        assertTrue(path.contains(100f, 20f), "the middle of a rhombus is inside")
        assertFalse(path.contains(2f, 2f), "a rhombus has no corners left")
    }

    @Test
    fun `the vertical axis cuts the other pair of ends`() {
        val path = outline(
            ChamferedRectShape(startCutDp = 10f, endCutDp = 10f, axis = ChamferAxis.Vertical),
            40f, 200f,
        )
        assertFalse(path.contains(1f, 6f), "the top point must be cut")
        assertTrue(path.contains(1f, 100f), "the middle of the long side is inside")
        assertFalse(path.contains(1f, 194f), "the bottom point must be cut")
    }

    @Test
    fun `rounding pulls the points back along the cut axis and leaves the other one alone`() {
        val plain = tight(ChamferedRectShape(startCutDp = 12f, endCutDp = 12f), 240f, 40f)
        val round = tight(ChamferedRectShape(startCutDp = 12f, endCutDp = 12f, roundingDp = 3f), 240f, 40f)
        // Across the cut the plane still meets its box, which is what keeps a
        // surface using this shape from showing a seam along its long edges.
        assertEquals(plain.top, round.top, 0.5f)
        assertEquals(plain.bottom, round.bottom, 0.5f)
        // Along it each point retreats, equally on both ends, and by less than
        // the radius. Rounding an apex cannot do otherwise.
        val leftGive = round.left - plain.left
        val rightGive = plain.right - round.right
        assertTrue(leftGive > 0.5f, "a rounded point must stand off the edge, gave $leftGive")
        assertEquals(leftGive, rightGive, 0.5f)
        assertTrue(leftGive < 6f, "the give must stay under the 3dp (6px) radius, was $leftGive")
    }
}
