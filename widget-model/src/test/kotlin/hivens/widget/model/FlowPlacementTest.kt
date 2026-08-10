package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `weight`, `canvas` and `cell` sit on every widget at once, though at most one
 * of them means anything for the slot it happens to be in. The renderer's
 * precedence used to be two copies of a `when` inside a composable, which is
 * both untestable and free to drift; this pins the rule that decides.
 *
 * The fields a slot ignores stay on the instance rather than being cleared --
 * flipping a slot's orientation and back must not cost the user their
 * arrangement -- so "ignored" is asserted here, not enforced by wiping data.
 */
class FlowPlacementTest {

    private fun widget(
        weight: Float = 0f,
        canvas: CanvasPlacement? = null,
        cell: GridCell? = null,
    ) = WidgetInstance(kind = WidgetKind("test.widget"), instanceId = "id", weight = weight, canvas = canvas, cell = cell)

    @Test
    fun `no placement is natural size`() {
        assertEquals(FlowPlacement.Natural, widget().flowPlacement())
    }

    @Test
    fun `weight wins over an explicit size`() {
        val placement = widget(weight = 2f, canvas = CanvasPlacement(width = 300f, height = 200f)).flowPlacement()
        assertEquals(
            FlowPlacement.Weighted(2f),
            placement,
            "resizing a weighted widget must not strip its flex, or the weighted region stops filling",
        )
    }

    @Test
    fun `a resized widget is bounded on the axes it set`() {
        assertEquals(
            FlowPlacement.Bounded(widthDp = 300f, heightDp = 0f),
            widget(canvas = CanvasPlacement(width = 300f)).flowPlacement(),
        )
        assertEquals(
            FlowPlacement.Bounded(widthDp = 0f, heightDp = 120f),
            widget(canvas = CanvasPlacement(height = 120f)).flowPlacement(),
        )
    }

    @Test
    fun `a canvas offset alone does not size anything in a flow slot`() {
        assertEquals(
            FlowPlacement.Natural,
            widget(canvas = CanvasPlacement(x = 40f, y = 80f, z = 3)).flowPlacement(),
            "position belongs to a canvas slot; a flow slot places by order",
        )
    }

    @Test
    fun `a grid cell is ignored by a flow slot`() {
        assertEquals(
            FlowPlacement.Natural,
            widget(cell = GridCell(col = 2, row = 1, colSpan = 2)).flowPlacement(),
        )
    }

    @Test
    fun `zero weight is not a weight`() {
        assertEquals(FlowPlacement.Natural, widget(weight = 0f).flowPlacement())
    }
}
