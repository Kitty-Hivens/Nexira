package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a slot's arrangement looks like on disk, and what happens when a build
 * meets a value it does not know.
 *
 * The mode used to be an enum on the wire, which is the one thing the format
 * promised it would not carry: an older build reading a newer constant had to
 * either throw or guess, so it needed a sentinel, a lenient codec and a test
 * that an older build would not write the sentinel back over the real value.
 * None of that exists now, because the mode is a direction, a line length and a
 * unit, and every one of them degrades on its own.
 */
class SlotWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun slot(text: String): SlotContent = json.decodeFromString(SlotContent.serializer(), text)

    @Test
    fun `a slot with no flow key at all is a plain column`() {
        val content = slot("""{"widgets":[]}""")
        assertEquals(FlowSpec.Column, content.flow)
        assertEquals(0, content.grid, "a flow slot measures nothing, so its unit is unset")
    }

    @Test
    fun `an explicit null flow is a placement slot`() {
        val content = slot("""{"widgets":[],"flow":null,"grid":4}""")
        assertNull(content.flow, "null is the mode, not a missing value")
        assertEquals(4, content.grid)
    }

    @Test
    fun `an unrecognised direction reads as vertical and the siblings survive`() {
        val content = slot("""{"widgets":[],"flow":{"direction":"isometric","wrap":3,"uniform":true}}""")
        val flow = content.flow!!
        assertTrue(!flow.horizontal, "a direction this build does not know falls back rather than throwing")
        assertEquals(3, flow.wrap, "the fields beside it are not collateral")
        assertTrue(flow.uniform)
    }

    @Test
    fun `an unrecognised anchor reads as the top start corner`() {
        assertEquals(Placement.TOP_START, parseAnchor("north-by-northwest"))
        assertEquals(Placement.TOP_START, parseAnchor(""))
    }

    @Test
    fun `an anchor is read case-insensitively and trimmed`() {
        assertEquals(Placement.BOTTOM_END, parseAnchor("  BOTTOMEND "))
        assertEquals(Placement.CENTER, parseAnchor("Center"))
    }

    @Test
    fun `an unknown anchor is not written back over the value it did not understand`() {
        // The old enum re-serialised its sentinel, so an older build that merely
        // opened a file could overwrite a corner it could not draw. The anchor is
        // a string: what was read is what is still there.
        val widget = json.decodeFromString(
            WidgetInstance.serializer(),
            """{"kind":"k","instance_id":"i","placement":{"anchor":"quadrant","x":2.0}}""",
        )
        assertEquals("quadrant", widget.placement?.anchor, "the file keeps what it said")
        assertEquals(Placement.TOP_START, parseAnchor(widget.placement!!.anchor), "the renderer still gets an answer")
    }

    @Test
    fun `a widget with no placement carries none, which is what seeding looks for`() {
        val widget = json.decodeFromString(WidgetInstance.serializer(), """{"kind":"k","instance_id":"i"}""")
        assertNull(widget.placement)
    }
}

/**
 * The anchor decides two things that have to be the same decision: which corner
 * the renderer measures an offset from, and which way a drag moves that number.
 *
 * They were two separate expressions once, and only one of them existed: the
 * renderer ran the offset inward from an end edge and the gesture added to it
 * regardless, so a widget anchored to a corner walked away from the pointer.
 */
class AnchorDragSignTest {

    @Test
    fun `the drag sign follows the same bias the renderer draws from`() {
        for (anchor in Placement.ANCHORS) {
            val expectedX = if (anchorHorizontalBias(anchor) > 0.5f) -1f else 1f
            val expectedY = if (anchorVerticalBias(anchor) > 0.5f) -1f else 1f
            assertEquals(expectedX, anchorDragSignX(anchor), "x sign disagrees with the bias for $anchor")
            assertEquals(expectedY, anchorDragSignY(anchor), "y sign disagrees with the bias for $anchor")
        }
    }

    @Test
    fun `an offset from an end edge grows as the pointer moves away from it`() {
        assertEquals(-1f, anchorDragSignX(Placement.BOTTOM_END))
        assertEquals(-1f, anchorDragSignY(Placement.BOTTOM_END))
        assertEquals(1f, anchorDragSignX(Placement.TOP_START))
        assertEquals(1f, anchorDragSignY(Placement.TOP_START))
        assertEquals(1f, anchorDragSignX(Placement.CENTER), "a centred nudge is not an inset")
    }

    @Test
    fun `an unrecognised anchor drags the way the default one draws`() {
        assertEquals(anchorDragSignX(Placement.TOP_START), anchorDragSignX("sideways"))
        assertEquals(anchorDragSignY(Placement.TOP_START), anchorDragSignY("sideways"))
    }
}

/**
 * How far a placed widget may travel before the clamp holds it.
 *
 * The bias is the whole of it. A clamp written for the top left is correct for
 * exactly one of the nine anchors, and lets a centred one walk a full slot width
 * out of reach before it notices, which is what a real layout file recorded:
 * anchored to the bottom centre, offset 2105, on a slot around two thousand wide.
 */
class PlacementClampTest {

    private val slot = 1000f
    private val widget = 100f

    /** Where the widget's near edge lands for an offset counted from [bias]. */
    private fun nearEdge(offset: Float, bias: Float): Float {
        val sign = if (bias > 0.5f) -1f else 1f
        return bias * (slot - widget) + sign * offset
    }

    @Test
    fun `whatever the anchor, what is left inside is never less than the margin`() {
        // The property, not a number per anchor. Writing the numbers out is how the
        // first version of this test came to disagree with a clamp that was right:
        // an end anchor holds at an offset of 976 on a slot of 1000, which reads
        // like an escape until you work out that it puts the near edge at -76 and
        // leaves exactly the margin showing.
        for (bias in listOf(0f, 0.5f, 1f)) {
            for (raw in listOf(-9999f, -500f, 0f, 300f, 500f, 2105f, 9999f)) {
                val edge = nearEdge(clampPlacementAxis(raw, slot, widget, bias), bias)
                assertTrue(
                    edge >= GRAB_MARGIN_DP - widget - 0.01f && edge <= slot - GRAB_MARGIN_DP + 0.01f,
                    "bias $bias, offset $raw left the near edge at $edge",
                )
            }
        }
    }

    @Test
    fun `a value already inside is not moved`() {
        for (bias in listOf(0f, 0.5f, 1f)) {
            assertEquals(120f, clampPlacementAxis(120f, slot, widget, bias), "bias $bias moved a value that fits")
        }
    }

    @Test
    fun `a centred widget cannot be pushed out of a slot it fits in`() {
        // The real case: bottom centre, offset 2105, on a slot around two thousand
        // wide. A clamp written for the top left let it through.
        val held = clampPlacementAxis(2105f, slotDp = 2129f, widgetDp = 320f, bias = 0.5f)
        val edge = 0.5f * (2129f - 320f) + held
        assertTrue(edge + 320f > 0f && edge < 2129f, "the widget ended up entirely outside: near edge $edge")
    }

    @Test
    fun `an unmeasured slot clamps nothing rather than pinning to zero`() {
        assertEquals(400f, clampPlacementAxis(400f, slotDp = 0f, widgetDp = widget, bias = 0f))
    }

    @Test
    fun `a slot too small for the margins leaves the value alone`() {
        assertEquals(400f, clampPlacementAxis(400f, slotDp = 10f, widgetDp = 0f, bias = 0f))
    }
}
