package hivens.ui.screens.console

import kotlin.test.Test
import kotlin.test.assertEquals

class HeightIndexTest {

    @Test
    fun constantMapsOffsetAndTopUniformly() {
        val idx = ConstantHeightIndex(lineHeightPx = 20)
        idx.reset(count = 10, estimatePerLine = 999) // estimate ignored for constant
        assertEquals(200, idx.totalHeight)
        assertEquals(60, idx.topOfLine(3))
        assertEquals(0, idx.lineAtOffset(0))
        assertEquals(3, idx.lineAtOffset(60))   // exactly the top of line 3
        assertEquals(3, idx.lineAtOffset(75))   // inside line 3
        assertEquals(9, idx.lineAtOffset(199))
        assertEquals(9, idx.lineAtOffset(10_000)) // clamped past the end
    }

    @Test
    fun constantSetHeightIsInert() {
        val idx = ConstantHeightIndex(20)
        idx.reset(5, 0)
        idx.setHeight(2, 99)
        assertEquals(100, idx.totalHeight) // unchanged: constant height
        assertEquals(40, idx.topOfLine(2))
    }

    @Test
    fun fenwickSeedsFromEstimate() {
        val idx = FenwickHeightIndex()
        idx.reset(count = 5, estimatePerLine = 10)
        assertEquals(50, idx.totalHeight)
        assertEquals(20, idx.topOfLine(2))
        assertEquals(2, idx.lineAtOffset(25))
        assertEquals(2, idx.lineAtOffset(20)) // top boundary of line 2
        assertEquals(0, idx.lineAtOffset(0))
        assertEquals(4, idx.lineAtOffset(49))
    }

    @Test
    fun fenwickCorrectsHeightsInPlace() {
        val idx = FenwickHeightIndex(initialCount = 5, estimatePerLine = 10)
        idx.setHeight(1, 30) // line 1 grows 10 -> 30; heights = [10,30,10,10,10]
        assertEquals(70, idx.totalHeight)
        assertEquals(10, idx.topOfLine(1))
        assertEquals(40, idx.topOfLine(2))
        assertEquals(1, idx.lineAtOffset(10)) // top of the now-tall line 1
        assertEquals(1, idx.lineAtOffset(39)) // still inside line 1 ([10,40))
        assertEquals(2, idx.lineAtOffset(40)) // top of line 2

        idx.setHeight(0, 5) // heights = [5,30,10,10,10]
        assertEquals(65, idx.totalHeight)
        assertEquals(0, idx.lineAtOffset(4))
        assertEquals(1, idx.lineAtOffset(5)) // top of line 1 shifted up
    }

    @Test
    fun fenwickOffsetTopRoundTrip() {
        val idx = FenwickHeightIndex(initialCount = 8, estimatePerLine = 12)
        // Give each line a distinct height so boundaries are unambiguous.
        val hs = intArrayOf(7, 24, 12, 36, 5, 48, 12, 19)
        hs.forEachIndexed { i, h -> idx.setHeight(i, h) }
        var expectedTop = 0
        for (i in hs.indices) {
            assertEquals(expectedTop, idx.topOfLine(i), "top of line $i")
            assertEquals(i, idx.lineAtOffset(expectedTop), "line at top of $i")
            assertEquals(i, idx.lineAtOffset(expectedTop + hs[i] - 1), "line at bottom of $i")
            expectedTop += hs[i]
        }
        assertEquals(expectedTop, idx.totalHeight)
    }

    @Test
    fun emptyIsSafe() {
        val c = ConstantHeightIndex(20); c.reset(0, 0)
        assertEquals(0, c.totalHeight)
        assertEquals(0, c.lineAtOffset(50))
        val f = FenwickHeightIndex(); f.reset(0, 0)
        assertEquals(0, f.totalHeight)
        assertEquals(0, f.lineAtOffset(50))
    }
}
