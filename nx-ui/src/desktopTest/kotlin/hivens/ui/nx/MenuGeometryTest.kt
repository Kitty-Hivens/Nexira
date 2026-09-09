package hivens.ui.nx

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Where a menu lands and where its unfold starts.
 *
 * Both are decided in a position provider, which is a pure function of the anchor,
 * the window and the popup's measured size -- so it is checkable without a frame.
 * The one it replaced reported a fixed top-right origin whatever it did, which is
 * why a menu that flipped above its button grew downward out of thin air.
 */
class MenuGeometryTest {

    private val window = IntSize(1000, 800)
    private val popup = IntSize(200, 300)
    private val gap = 4

    private fun place(
        anchor: IntRect,
        align: NxMenuAlign,
    ): Pair<IntOffset, TransformOrigin> {
        val origin = mutableStateOf(TransformOrigin(0f, 0f))
        val at = MenuBelowAnchor(align, gap, origin)
            .calculatePosition(anchor, window, LayoutDirection.Ltr, popup)
        return at to origin.value
    }

    @Test
    fun `an end-aligned menu hangs its trailing edge on the trigger's`() {
        val anchor = IntRect(left = 800, top = 100, right = 840, bottom = 140)
        val (at, origin) = place(anchor, NxMenuAlign.End)
        assertEquals(IntOffset(640, 144), at)
        assertEquals(0f, origin.pivotFractionY, "growing down starts at the top edge")
        // The trigger's centre is 820, which is 180 into a 200-wide menu.
        assertEquals(0.9f, origin.pivotFractionX, 0.001f)
    }

    @Test
    fun `a start-aligned menu hangs its leading edge on the trigger's`() {
        val anchor = IntRect(left = 800, top = 100, right = 840, bottom = 140)
        val (at, origin) = place(anchor, NxMenuAlign.Start)
        assertEquals(IntOffset(800, 144), at)
        assertEquals(0.1f, origin.pivotFractionX, 0.001f)
    }

    @Test
    fun `no room below flips the menu above the trigger and the origin with it`() {
        val anchor = IntRect(left = 800, top = 600, right = 840, bottom = 640)
        val (at, origin) = place(anchor, NxMenuAlign.End)
        assertEquals(600 - 300 - gap, at.y, "the menu's bottom edge sits on the trigger's top edge")
        assertEquals(1f, origin.pivotFractionY, "growing up has to start at the bottom edge")
    }

    @Test
    fun `a menu that would overrun the right edge is clamped into the window`() {
        val anchor = IntRect(left = 950, top = 100, right = 990, bottom = 140)
        val (at, origin) = place(anchor, NxMenuAlign.Start)
        assertEquals(800, at.x, "clamped to window width less the popup")
        // Clamping moved the menu, so the origin has to move the other way to stay
        // on the trigger: the centre is at 970, which is 170 into the menu.
        assertEquals(0.85f, origin.pivotFractionX, 0.001f)
    }

    @Test
    fun `a wide trigger under a narrow menu still unfolds from the trigger`() {
        // A field 400 wide with a 200-wide list under it: a corner origin would start
        // the unfold at the edge of the list, which is nowhere near the field's middle.
        val anchor = IntRect(left = 100, top = 100, right = 500, bottom = 140)
        val (at, origin) = place(anchor, NxMenuAlign.Start)
        assertEquals(100, at.x)
        assertTrue(
            origin.pivotFractionX > 0.99f,
            "the trigger's centre is right of the list, so the origin pins to its edge: ${origin.pivotFractionX}",
        )
    }

    @Test
    fun `a zero-sized popup reports a centre origin rather than dividing by it`() {
        val origin = mutableStateOf(TransformOrigin(0f, 0f))
        MenuBelowAnchor(NxMenuAlign.End, gap, origin)
            .calculatePosition(IntRect(0, 0, 40, 40), window, LayoutDirection.Ltr, IntSize.Zero)
        assertEquals(0.5f, origin.value.pivotFractionX)
    }
}
