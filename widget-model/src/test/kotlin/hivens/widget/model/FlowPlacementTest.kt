package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A widget carries one [Placement] whatever slot it sits in, and a flow reads
 * three of its seven fields. The renderer's precedence used to be two copies of
 * a `when` inside a composable, which is both untestable and free to drift; this
 * pins the rule that decides.
 *
 * The fields a flow ignores stay on the instance rather than being cleared --
 * flipping a slot's mode and back must not cost the user their arrangement --
 * so "ignored" is asserted here, not enforced by wiping data.
 */
class FlowPlacementTest {

    private fun widget(placement: Placement? = null) =
        WidgetInstance(kind = WidgetKind("test.widget"), instanceId = "id", placement = placement)

    @Test
    fun `no placement at all is natural size`() {
        assertEquals(FlowPlacement.Natural, widget().flowPlacement())
    }

    @Test
    fun `weight wins over an explicit size`() {
        assertEquals(
            FlowPlacement.Weighted(2f),
            widget(Placement(weight = 2f, width = 300f, height = 200f)).flowPlacement(),
            "resizing a weighted widget must not strip its flex, or the weighted region stops filling",
        )
    }

    @Test
    fun `a resized widget is bounded on the axes it set`() {
        assertEquals(
            FlowPlacement.Bounded(widthDp = 300f, heightDp = 0f),
            widget(Placement(width = 300f)).flowPlacement(),
        )
        assertEquals(
            FlowPlacement.Bounded(widthDp = 0f, heightDp = 120f),
            widget(Placement(height = 120f)).flowPlacement(),
        )
    }

    @Test
    fun `an offset alone does not size anything in a flow slot`() {
        assertEquals(
            FlowPlacement.Natural,
            widget(Placement(x = 40f, y = 80f, z = 3)).flowPlacement(),
            "position belongs to a placement slot; a flow places by order",
        )
    }

    @Test
    fun `an anchor alone does not size anything in a flow slot`() {
        assertEquals(
            FlowPlacement.Natural,
            widget(Placement(anchor = Placement.BOTTOM_END)).flowPlacement(),
        )
    }

    @Test
    fun `zero weight is not a weight`() {
        assertEquals(FlowPlacement.Natural, widget(Placement(weight = 0f)).flowPlacement())
    }
}
