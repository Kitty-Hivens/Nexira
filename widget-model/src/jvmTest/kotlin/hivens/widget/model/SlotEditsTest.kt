package hivens.widget.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Edits of one slot's content, each handing back the very object it was given where it changes nothing. */
class SlotEditsTest {

    private val w1 = WidgetInstance(WidgetKind("a"), "i1", JsonObject(emptyMap()))
    private val w2 = WidgetInstance(WidgetKind("b"), "i2", JsonObject(emptyMap()))
    private val w3 = WidgetInstance(WidgetKind("c"), "i3", JsonObject(emptyMap()))
    private val w4 = WidgetInstance(WidgetKind("d"), "i4", JsonObject(emptyMap()))

    private fun slot(vararg widgets: WidgetInstance) = SlotContent(widgets.toList())

    private fun SlotContent.placementOf(id: String): Placement? = widgets.first { it.instanceId == id }.placement

    // ── Widgets ───────────────────────────────────────────────────────

    @Test
    fun `a widget put in at zero pushes the others back, and one past the end is appended`() {
        assertEquals(listOf(w3, w1, w2), slot(w1, w2).withWidget(w3, 0).widgets)
        assertEquals(listOf(w1, w2), slot(w1).withWidget(w2, 999).widgets)
    }

    @Test
    fun `a widget is taken out by its name, and one that is not there leaves the slot as it is`() {
        assertEquals(listOf(w1, w3), slot(w1, w2, w3).withoutWidget("i2").widgets)
        val content = slot(w1)
        assertSame(content, content.withoutWidget("ghost"))
    }

    @Test
    fun `a widget's props change on that widget only, and an edit of nobody is no edit`() {
        val props = buildJsonObject { put("title", "Hi") }
        val out = slot(w1, w2).withWidgetUpdated("i2") { it.copy(props = props) }
        assertEquals(props, out.widgets.first { it.instanceId == "i2" }.props)
        assertEquals(JsonObject(emptyMap()), out.widgets.first { it.instanceId == "i1" }.props)
        val content = slot(w1)
        assertSame(content, content.withWidgetUpdated("ghost") { it.copy(props = props) })
        assertSame(content, content.withWidgetUpdated("i1") { it })
    }

    @Test
    fun `a widget moves to another place in its slot, and a move to where it is or from nowhere is no move`() {
        assertEquals(listOf(w2, w3, w1), slot(w1, w2, w3).reordered(fromIndex = 0, toIndex = 2).widgets)
        val content = slot(w1, w2)
        assertSame(content, content.reordered(0, 0))
        assertSame(content, content.reordered(5, 0))
    }

    // ── Arrangement ───────────────────────────────────────────────────

    @Test
    fun `a slot changes its flow, and the flow it has already is no change`() {
        assertEquals(FlowSpec.Row, slot(w1).withFlow(FlowSpec.Row).flow)
        val content = slot(w1)
        assertSame(content, content.withFlow(FlowSpec.Column))
    }

    @Test
    fun `a grid is a horizontal flow that wraps into equal cells`() {
        val flow = slot(w1).withFlow(FlowSpec.grid(3)).flow!!
        assertEquals(true, flow.horizontal)
        assertEquals(3, flow.wrap)
        assertEquals(true, flow.uniform)
    }

    @Test
    fun `the lattice a slot measures in is clamped, and changing it rescales nothing already placed`() {
        assertEquals(3, slot(w1).withGrid(3).grid)
        assertEquals(0, slot(w1).withGrid(-4).grid)
        assertEquals(GRID_MAX, slot(w1).withGrid(999).grid)
        val placed = slot(w1).withPlacement("i1") { it.copy(x = 7f, y = 2f) }
        assertEquals(Placement(x = 7f, y = 2f), placed.withGrid(6).placementOf("i1"), "the number means cells now rather than dp, and reinterpreting it is the person's call")
    }

    // ── Placement ─────────────────────────────────────────────────────

    @Test
    fun `a placement is set on the matching widget only, its fields composing without clobbering`() {
        val out = slot(w1, w2)
            .withPlacement("i2") { it.copy(x = 10f, y = 20f) }
            .withPlacement("i2") { it.copy(width = 100f, height = 50f) }
            .withPlacement("i2") { it.copy(z = 3) }
        assertEquals(Placement(x = 10f, y = 20f, width = 100f, height = 50f, z = 3), out.placementOf("i2"))
        assertNull(out.placementOf("i1"))
    }

    @Test
    fun `writing a placement field the value it has, or on nobody, is no edit`() {
        val placed = slot(w1).withPlacement("i1") { it.copy(x = 5f) }
        assertSame(placed, placed.withPlacement("i1") { it.copy(x = 5f) })
        assertSame(placed, placed.withPlacement("ghost") { it.copy(x = 1f) })
        val bare = slot(w1)
        assertSame(bare, bare.withPlacement("i1") { it.copy(weight = 0f) }, "writing the default onto an unplaced widget mints no record")
    }

    @Test
    fun `a widget placed at the origin stays placed`() {
        // The record equals the default there, and normalising it away would make
        // the widget read as unseeded: the next flip, move or neighbour's drag
        // would pick it up and put it somewhere else.
        val placed = slot(w1).withPlacement("i1") { it.copy(x = 40f, y = 40f) }.withPlacement("i1") { it.copy(x = 0f, y = 0f) }
        assertEquals(Placement(), placed.placementOf("i1"))
        assertEquals(Placement(), placed.withFlow(null).placementOf("i1"), "a slot flipping to placement must not seed a widget that is already placed")
    }

    // ── Seeding when a slot becomes a placement slot ───────────────────

    @Test
    fun `a slot flipped to placement seeds a staggered grid onto the unplaced`() {
        val out = slot(w1, w2, w3, w4).withFlow(null)
        assertEquals(Placement(x = 16f, y = 16f, z = 0), out.placementOf("i1"))
        assertEquals(Placement(x = 236f, y = 16f, z = 1), out.placementOf("i2"))
        assertEquals(Placement(x = 456f, y = 16f, z = 2), out.placementOf("i3"))
        assertEquals(Placement(x = 16f, y = 176f, z = 3), out.placementOf("i4"), "wraps to the next row")
        assertNull(out.flow)
    }

    @Test
    fun `a slot flipped to placement keeps what is placed and seeds the rest at their index`() {
        val pre = slot(w1, w2).withPlacement("i1") { it.copy(x = 500f, y = 500f, z = 9) }
        val out = pre.withFlow(null)
        assertEquals(Placement(x = 500f, y = 500f, z = 9), out.placementOf("i1"))
        assertEquals(Placement(x = 236f, y = 16f, z = 1), out.placementOf("i2"))
    }

    @Test
    fun `a flow changed to another flow only flips, and placement twice is once`() {
        val flipped = slot(w1, w2).withFlow(FlowSpec.Row)
        assertNull(flipped.placementOf("i1"))
        assertNull(flipped.placementOf("i2"))
        val once = slot(w1, w2).withFlow(null)
        assertSame(once, once.withFlow(null))
    }

    @Test
    fun `seedPlacement staggers free placement into rows of three`() {
        assertEquals(Placement(x = 16f, y = 16f, z = 0), seedPlacement(0, grid = 0))
        assertEquals(Placement(x = 456f, y = 16f, z = 2), seedPlacement(2, grid = 0))
        assertEquals(Placement(x = 16f, y = 176f, z = 3), seedPlacement(3, grid = 0))
    }

    @Test
    fun `seedPlacement in a lattice takes the first free cell in reading order`() {
        val taken = listOf(
            WidgetInstance(WidgetKind("k"), "a", placement = Placement(x = 0f, y = 0f, width = 1f, height = 1f)),
            WidgetInstance(WidgetKind("k"), "b", placement = Placement(x = 1f, y = 0f, width = 1f, height = 1f)),
        )
        assertEquals(
            Placement(x = 0f, y = 1f, width = 1f, height = 1f),
            seedPlacement(index = 2, grid = 2, existing = taken),
            "two columns, both of row zero spoken for, so the next one is the row below",
        )
    }

    @Test
    fun `a slot flipped to a lattice seeds one-by-one cells in reading order`() {
        val out = slot(w1, w2, w3, w4).withGrid(2).withFlow(null)
        val cells = out.widgets.associate { it.instanceId to (it.placement?.x to it.placement?.y) }
        assertEquals(0f to 0f, cells["i1"])
        assertEquals(1f to 0f, cells["i2"])
        assertEquals(0f to 1f, cells["i3"])
        assertEquals(1f to 1f, cells["i4"])
    }

    // ── Lattice collision: snap, never evict, never compact ───────────

    private fun latticeContent(vararg pairs: Pair<String, Placement>): SlotContent =
        SlotContent(
            widgets = pairs.map { (id, p) -> WidgetInstance(WidgetKind("k"), id, JsonObject(emptyMap()), placement = p) },
            flow    = null,
            grid    = 4,
        )

    private fun cell(col: Int, row: Int, w: Int = 1, h: Int = 1) =
        Placement(x = col.toFloat(), y = row.toFloat(), width = w.toFloat(), height = h.toFloat())

    @Test
    fun `placeInGrid snaps the moved widget to a free target, others fixed`() {
        val out = placeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(2, 0)), "a", cell(1, 0), columns = 4)
        assertEquals(cell(1, 0), out.placementOf("a"))
        assertEquals(cell(2, 0), out.placementOf("b"))
    }

    @Test
    fun `placeInGrid snaps to the nearest free cell when the target is occupied`() {
        // a -> b's cell (1,0): occupied, so a snaps to the nearest free one; b never moves.
        val out = placeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(1, 0)), "a", cell(1, 0), columns = 4)
        assertEquals(cell(0, 0), out.placementOf("a"))
        assertEquals(cell(1, 0), out.placementOf("b"))
    }

    @Test
    fun `placeInGrid never compacts -- gaps are preserved`() {
        // b floats at row 3; moving a must NOT pull b upward. This is a snap grid,
        // not a packer, and a gap somebody left is a gap they meant.
        val out = placeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(0, 3)), "a", cell(0, 0), columns = 4)
        assertEquals(cell(0, 0), out.placementOf("a"))
        assertEquals(cell(0, 3), out.placementOf("b"))
    }

    @Test
    fun `placeInGrid clamps span and column into the lattice`() {
        val out = placeInGrid(latticeContent("a" to cell(0, 0)), "a", cell(3, 0, w = 5), columns = 4)
        val a = out.placementOf("a")!!
        assertEquals(4f, a.width) // 5 clamped to the column count
        assertEquals(0f, a.x)     // and the anchor clamped into [0, columns - span]
    }

    @Test
    fun `placeInGrid is identity when nothing moves`() {
        val c = latticeContent("a" to cell(0, 0), "b" to cell(1, 0))
        assertSame(c, placeInGrid(c, "a", cell(0, 0), columns = 4))
    }

    @Test
    fun `resizeInGrid clamps the span so it cannot grow over a neighbour`() {
        val out = resizeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(1, 0)), "a", width = 3f, height = 1f, columns = 4)
        assertEquals(1f, out.placementOf("a")!!.width)
        assertEquals(cell(1, 0), out.placementOf("b"))
    }

    @Test
    fun `resizeInGrid grows into free space`() {
        val out = resizeInGrid(latticeContent("a" to cell(0, 0), "b" to cell(3, 0)), "a", width = 2f, height = 2f, columns = 4)
        val a = out.placementOf("a")!!
        assertEquals(2f, a.width)
        assertEquals(2f, a.height)
    }
}
