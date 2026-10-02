package hivens.ui.widgets.home.new

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** The recent row fills the width it is given rather than stopping at a fixed count. */
class RecentTileGridTest {

    @Test
    fun `a wider row holds more columns`() {
        assertEquals(6, tileColumns(1400.dp, 220.dp))
        assertEquals(9, tileColumns(2100.dp, 220.dp))
    }

    @Test
    fun `a row narrower than one tile still holds one`() {
        assertEquals(1, tileColumns(120.dp, 220.dp))
    }

    @Test
    fun `the gaps count against the width`() {
        // Four tiles of 220 need 880 plus three gaps of 10.
        assertEquals(3, tileColumns(900.dp, 220.dp))
        assertEquals(4, tileColumns(910.dp, 220.dp))
    }

    @Test
    fun `rows multiply the grid and a named ceiling caps it`() {
        assertEquals(12, tileBudget(columns = 6, rows = 2, maxTiles = 0))
        assertEquals(5, tileBudget(columns = 6, rows = 2, maxTiles = 5))
        assertEquals(6, tileBudget(columns = 6, rows = 0, maxTiles = 0))
    }
}
