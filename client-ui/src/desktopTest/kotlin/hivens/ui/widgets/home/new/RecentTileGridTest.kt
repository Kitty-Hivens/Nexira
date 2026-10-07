package hivens.ui.widgets.home.new

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Themes
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

/** The pack list, on a Home that does not scroll and on a page that does. */
class PackListFitTest {

    @Test
    fun `a bounded list keeps every row and scrolls to the ones below`() {
        val state = ScrollState(0)
        val height = listHeight(rows = 10, bound = 200, state = state)
        assertEquals(200, height)
        assertEquals(10 * ROW - 200, state.maxValue)
    }

    @Test
    fun `a short list is as tall as its rows, not as the height on offer`() {
        assertEquals(2 * ROW, listHeight(rows = 2, bound = 400))
    }

    @Test
    fun `an unbounded list lays out in full and leaves the scrolling to the page`() {
        assertEquals(10 * ROW, listHeight(rows = 10, bound = null))
    }

    @Test
    fun `columns leave in the order they are least needed`() {
        assertEquals(PackListColumns(runsOn = false, played = false), PackListColumns.forWidth(400f))
        assertEquals(PackListColumns(runsOn = true, played = false), PackListColumns.forWidth(600f))
        assertEquals(PackListColumns(runsOn = true, played = true), PackListColumns.forWidth(900f))
    }

    /**
     * The height the list draws at, with [rows] rows of [ROW] under a parent that
     * bounds it at [bound] or, for null, scrolls itself and so bounds nothing.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun listHeight(rows: Int, bound: Int?, state: ScrollState = ScrollState(0)): Int {
        var measured = -1
        val scene = ImageComposeScene(width = 600, height = 800, density = Density(1f)) {
            NxTheme(Themes.Celestia, dark = true) {
                val parent = if (bound != null) {
                    Modifier.heightIn(max = bound.dp)
                } else {
                    Modifier.verticalScroll(rememberScrollState())
                }
                Box(parent.fillMaxWidth()) {
                    Box(Modifier.onSizeChanged { measured = it.height }) {
                        ScrollingRows(bounded = bound != null, state = state) {
                            repeat(rows) { Box(Modifier.fillMaxWidth().height(ROW.dp)) }
                        }
                    }
                }
            }
        }
        scene.render()
        scene.close()
        return measured
    }

    private companion object {
        const val ROW = 52
    }
}
