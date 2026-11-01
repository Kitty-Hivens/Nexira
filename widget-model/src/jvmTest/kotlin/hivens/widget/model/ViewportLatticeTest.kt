package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A lattice in a slot that scrolls sideways counts rows, and every rule the
 * lattice has was written for columns.
 *
 * So each rule is checked twice here, the second time on the same arrangement
 * turned on its side: where a column-counting lattice clamps a column, a
 * row-counting one has to clamp the row, and seed, move and grow down its first
 * column the way the other does along its first row.
 */
class ViewportLatticeTest {

    private fun cell(col: Int, row: Int, w: Int = 1, h: Int = 1) =
        Placement(x = col.toFloat(), y = row.toFloat(), width = w.toFloat(), height = h.toFloat())

    private fun lattice(vararg pairs: Pair<String, Placement>, sideways: Boolean) = SlotContent(
        widgets = pairs.map { (id, p) -> WidgetInstance(WidgetKind("k"), id, placement = p) },
        flow = null,
        grid = 2,
        viewport = if (sideways) ViewportSpec.ScrollRight else null,
    )

    private fun SlotContent.at(id: String) = widgets.first { it.instanceId == id }.placement!!

    @Test
    fun `a row-counting lattice seeds down its first column`() {
        val taken = listOf(WidgetInstance(WidgetKind("k"), "a", placement = cell(0, 0)))
        assertEquals(cell(1, 0), seedPlacement(1, grid = 2, existing = taken), "a column lattice fills the first row")
        assertEquals(cell(0, 1), seedPlacement(1, grid = 2, existing = taken, transposed = true), "a row lattice fills the first column")
    }

    @Test
    fun `a row-counting lattice clamps the row and lets the column run on`() {
        val out = placeInGrid(lattice("a" to cell(0, 0), sideways = true), "a", cell(7, 5), columns = 2)
        assertEquals(7f, out.at("a").x, "the column is the side that grows")
        assertEquals(1f, out.at("a").y, "the row is held inside the two there are")
    }

    @Test
    fun `a column-counting lattice still clamps the column`() {
        val out = placeInGrid(lattice("a" to cell(0, 0), sideways = false), "a", cell(7, 5), columns = 2)
        assertEquals(1f, out.at("a").x)
        assertEquals(5f, out.at("a").y)
    }

    @Test
    fun `a row-counting lattice snaps away from a neighbour along the rows`() {
        val out = placeInGrid(lattice("a" to cell(0, 0), "b" to cell(0, 1), sideways = true), "a", cell(0, 1), columns = 2)
        assertTrue(out.at("a") != cell(0, 1), "it landed on its neighbour")
        assertEquals(cell(0, 1), out.at("b"), "and the neighbour did not move")
    }

    @Test
    fun `a row-counting lattice caps a span at the rows there are`() {
        val out = resizeInGrid(lattice("a" to cell(0, 0), sideways = true), "a", width = 4f, height = 9f, columns = 2)
        assertEquals(4f, out.at("a").width)
        assertEquals(2f, out.at("a").height)
    }

    @Test
    fun `a rule that moves nothing hands back the very slot it was given`() {
        val c = lattice("a" to cell(1, 1), sideways = true)
        assertSame(c, placeInGrid(c, "a", cell(1, 1), columns = 2))
    }

    // ── Turning the page ─────────────────────────────────────────────

    private val path = SlotPath(SurfaceId("s"), SlotId("main"))

    private fun graph(content: SlotContent) =
        LayoutGraph(surfaces = mapOf(SurfaceId("s") to SurfaceLayout(slots = mapOf(SlotId("main") to content))))

    @Test
    fun `a lattice that starts scrolling sideways is turned on its side, and back`() {
        val start = graph(lattice("a" to cell(1, 3, w = 1, h = 2), sideways = false))
        val sideways = start.setViewport(path, ViewportSpec.ScrollRight)
        assertEquals(cell(3, 1, w = 2, h = 1), sideways.traverse(path)!!.at("a"), "every widget lands in a row that exists")
        val back = sideways.setViewport(path, null)
        assertEquals(cell(1, 3, w = 1, h = 2), back.traverse(path)!!.at("a"), "and going back gives the arrangement back")
    }

    @Test
    fun `a lattice scrolling down keeps its placements`() {
        val start = graph(lattice("a" to cell(1, 3), sideways = false))
        assertEquals(cell(1, 3), start.setViewport(path, ViewportSpec.ScrollDown).traverse(path)!!.at("a"))
    }

    @Test
    fun `free placement is never turned`() {
        val free = SlotContent(
            widgets = listOf(WidgetInstance(WidgetKind("k"), "a", placement = Placement(x = 10f, y = 300f))),
            flow = null,
        )
        val out = graph(free).setViewport(path, ViewportSpec.ScrollRight)
        assertEquals(Placement(x = 10f, y = 300f), out.traverse(path)!!.at("a"), "a dp offset means the same on either axis")
    }

    @Test
    fun `a slot flipped to a row-counting lattice seeds into rows`() {
        val flow = SlotContent(
            widgets = listOf(WidgetInstance(WidgetKind("k"), "a"), WidgetInstance(WidgetKind("k"), "b"), WidgetInstance(WidgetKind("k"), "c")),
            grid = 2,
            viewport = ViewportSpec.ScrollRight,
        )
        val out = graph(flow).setFlow(path, null).traverse(path)!!
        assertEquals(listOf(cell(0, 0), cell(0, 1), cell(1, 0)), out.widgets.map { it.placement })
    }

    // ── The clamp on a page ──────────────────────────────────────────

    @Test
    fun `along a page a start-attached widget is held off the start and nowhere else`() {
        assertEquals(5000f, clampPlacementAxis(5000f, Float.POSITIVE_INFINITY, 50f, bias = 0f), "a page has no far edge")
        assertEquals(GRAB_MARGIN_DP - 50f, clampPlacementAxis(-9000f, Float.POSITIVE_INFINITY, 50f, bias = 0f))
    }
}
