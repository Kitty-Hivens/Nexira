package hivens.ui.layout

import hivens.widget.model.FamilyId
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A widget shipped after somebody's first launch has to reach them once, and only
 * once, and never on top of one they fetched themselves.
 *
 * The three cases are the whole rule, and the middle one is why the record exists
 * at all: without it the only way to deliver a new widget is to re-add every widget
 * every launch, which is the editor undoing the user's work on a timer.
 */
class LayoutSeedingTest {

    private fun widget(kind: String, id: String) =
        WidgetInstance(WidgetKind(kind), id, JsonObject(emptyMap()))

    private fun surface(vararg slots: Pair<String, List<WidgetInstance>>) =
        SurfaceLayout(slots = slots.associate { (s, w) -> SlotId(s) to SlotContent(w) })

    private fun graph(vararg widgets: WidgetInstance) =
        LayoutGraph(surfaces = mapOf(SurfaceId("bg") to surface("controls" to widgets.toList())))

    private fun kindsIn(g: LayoutGraph): List<String> =
        g.surfaces.getValue(SurfaceId("bg"))
            .family(FamilyId.GENERAL)!!
            .slots.getValue(SlotId("controls"))
            .widgets.map { it.kind.value }

    private val bundled = graph(
        widget("bg.enable", "bg-enable-default"),
        widget("bg.loop", "bg-loop-default"),
        widget("bg.audio", "bg-audio-default"),
        widget("bg.reset", "bg-reset-default"),
    )

    @Test
    fun `a widget the bundle gained after this file was written arrives, in its place`() {
        val user = graph(
            widget("bg.enable", "bg-enable-default"),
            widget("bg.loop", "bg-loop-default"),
            widget("bg.reset", "bg-reset-default"),
        )
        val out = LayoutSeeding.seed(user, bundled, offered = null)

        assertEquals(listOf("bg-audio-default"), out.added)
        // Third in the bundle and third here, rather than appended: a control added
        // between two others belongs where its author put it, and a panel that grows
        // only at the bottom reorders itself differently for every user.
        assertEquals(listOf("bg.enable", "bg.loop", "bg.audio", "bg.reset"), kindsIn(out.graph))
    }

    @Test
    fun `removing it afterwards is a decision, and the next launch honours it`() {
        val first = LayoutSeeding.seed(graph(widget("bg.enable", "bg-enable-default")), bundled, offered = null)
        assertTrue("bg-audio-default" in first.added, "the first pass must offer it at all")

        // What the user does next: takes it back out. The record keeps the offer.
        val without = graph(widget("bg.enable", "bg-enable-default"))
        val second = LayoutSeeding.seed(without, bundled, offered = first.offered)

        assertEquals(emptyList(), second.added, "an offered widget must not come back")
        assertEquals(listOf("bg.enable"), kindsIn(second.graph))
    }

    @Test
    fun `a control somebody fetched by hand is not delivered a second time`() {
        // The shape a real graph was in: the widget could not arrive on its own, so
        // it was dragged out of the palette, which mints an id of its own. By id
        // alone the bundled one is still missing, and seeding it would put two of
        // the same control in one panel.
        val user = graph(
            widget("bg.enable", "bg-enable-default"),
            widget("bg.audio", "9f2c-a-palette-uuid"),
        )
        val out = LayoutSeeding.seed(user, bundled, offered = null)

        // Both halves at once, which is the point: the one they already have is
        // withheld while the two they have never seen still arrive. A pass that
        // delivered nothing here would pass a weaker version of this test and hide
        // the case where a hand-added widget suppresses its whole slot.
        assertEquals(listOf("bg-loop-default", "bg-reset-default"), out.added.sorted())
        assertTrue("bg-audio-default" !in out.added, "the control they fetched must not be delivered again")
        assertEquals(1, kindsIn(out.graph).count { it == "bg.audio" }, "one audio control in the panel, not two")
        assertTrue(
            "bg-audio-default" in out.offered,
            "the bundled id has to be recorded as offered, or the next launch delivers the duplicate",
        )
    }

    @Test
    fun `a graph already holding everything records the offer and changes nothing`() {
        val out = LayoutSeeding.seed(bundled, bundled, offered = null)
        assertEquals(emptyList(), out.added)
        assertEquals(bundled, out.graph)
        assertEquals(4, out.offered.size)
    }
}
