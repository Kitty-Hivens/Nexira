package hivens.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class SlotDividerMathTest {

    @Test
    fun `drag toward the right grows the left widget`() {
        // pair 200px, left starts at 100, drag +30 -> left 130
        assertEquals(130f, dividerLeftWeight(startLeftPx = 100f, sumPx = 200f, accum = 30f, minPx = 40f))
    }

    @Test
    fun `drag toward the left shrinks the left widget`() {
        assertEquals(70f, dividerLeftWeight(startLeftPx = 100f, sumPx = 200f, accum = -30f, minPx = 40f))
    }

    @Test
    fun `clamps so neither side drops below min`() {
        // sum 200, min 40 -> left clamped to [40, 160]
        assertEquals(160f, dividerLeftWeight(100f, 200f, 999f, 40f))
        assertEquals(40f, dividerLeftWeight(100f, 200f, -999f, 40f))
    }

    @Test
    fun `degenerate pair smaller than two mins pins to min without crashing`() {
        // sum 60, min 40 -> upper = max(20,40) = 40, lower = min(40,40) = 40
        assertEquals(40f, dividerLeftWeight(30f, 60f, 10f, 40f))
        assertEquals(40f, dividerLeftWeight(30f, 60f, -10f, 40f))
    }
}
