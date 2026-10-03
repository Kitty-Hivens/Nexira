package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * How a slot's viewport reads off the wire, and how it degrades.
 *
 * A slot nobody touched has to say nothing, a kind this build does not know has
 * to land on static rather than fail the file, and putting a slot back to static
 * has to leave it as if nobody had touched it.
 */
class ViewportSpecTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun slot(text: String): SlotContent = json.decodeFromString(SlotContent.serializer(), text)

    @Test
    fun `a slot that names no viewport is static`() {
        val content = slot("""{"widgets":[]}""")
        assertNull(content.viewport)
        assertEquals(ViewportMode.Static, content.viewportMode)
    }

    @Test
    fun `a scroll reads with its axis`() {
        assertEquals(ViewportMode.Scroll(horizontal = false), slot("""{"viewport":{"kind":"scroll"}}""").viewportMode)
        assertEquals(
            ViewportMode.Scroll(horizontal = true),
            slot("""{"viewport":{"kind":"scroll","axis":"horizontal"}}""").viewportMode,
        )
    }

    @Test
    fun `a kind this build does not know reads as static and is kept as written`() {
        // A newer build's map, say. Static is the fallback because it is what every
        // slot was before the field existed: the content stays where it was put.
        val content = slot("""{"viewport":{"kind":"map","axis":"both"}}""")
        assertEquals(ViewportMode.Static, content.viewportMode)
        assertEquals("map", content.viewport?.kind, "the file keeps what it said")
    }

    @Test
    fun `an axis this build does not know reads as vertical`() {
        assertEquals(
            ViewportMode.Scroll(horizontal = false),
            slot("""{"viewport":{"kind":"scroll","axis":"diagonal"}}""").viewportMode,
        )
    }

    @Test
    fun `kind and axis forgive case and space`() {
        assertEquals(
            ViewportMode.Scroll(horizontal = true),
            slot("""{"viewport":{"kind":" Scroll ","axis":"HORIZONTAL"}}""").viewportMode,
        )
    }

    // ── The transform ────────────────────────────────────────────────

    private val path = SlotPath(SurfaceId("s"), SlotId("main"))

    private fun graph(content: SlotContent = SlotContent()) =
        LayoutGraph(surfaces = mapOf(SurfaceId("s") to SurfaceLayout(slots = mapOf(SlotId("main") to content))))

    @Test
    fun `setting a viewport writes it to the slot`() {
        val out = graph().setViewport(path, ViewportSpec.ScrollDown)
        assertEquals(ViewportSpec.ScrollDown, out.traverse(path)?.viewport)
    }

    @Test
    fun `putting a slot back to static clears the field`() {
        val scrolled = graph(SlotContent(viewport = ViewportSpec.ScrollRight))
        assertNull(scrolled.setViewport(path, ViewportSpec()).traverse(path)?.viewport)
        assertNull(scrolled.setViewport(path, null).traverse(path)?.viewport)
    }

    @Test
    fun `a write that changes nothing hands back the same graph`() {
        val g = graph(SlotContent(viewport = ViewportSpec.ScrollDown))
        assertSame(g, g.setViewport(path, ViewportSpec.ScrollDown))
        val static = graph()
        assertSame(static, static.setViewport(path, ViewportSpec()))
    }

    @Test
    fun `a slot that is not there is left alone`() {
        val g = graph()
        assertSame(g, g.setViewport(SlotPath(SurfaceId("s"), SlotId("missing")), ViewportSpec.ScrollDown))
    }
}
