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
