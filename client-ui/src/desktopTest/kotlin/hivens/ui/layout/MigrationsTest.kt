package hivens.ui.layout

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.walkInstances
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ladder that carries a saved layout across a schema change.
 *
 * The step under test retires a widget, and a retirement is the case where doing
 * nothing is worst: a kind the registry does not know is one the renderer skips,
 * so a person who had placed that widget would find a hole where it was, with
 * nothing on screen saying what happened. What is asserted is that the instance
 * survives and changes kind, rather than that the file merely still parses.
 */
class MigrationsTest {

    private fun graphOf(vararg widgets: WidgetInstance) = LayoutGraph(
        surfaces = mapOf(
            SurfaceId("home") to SurfaceLayout(
                slots = mapOf(SlotId("main") to SlotContent(widgets = widgets.toList())),
            ),
        ),
    )

    private fun widget(kind: String, id: String, props: JsonObject = JsonObject(emptyMap())) =
        WidgetInstance(kind = WidgetKind(kind), instanceId = id, props = props)

    @Test
    fun `the retired music player becomes the cover-led one and keeps its place`() {
        val graph = graphOf(
            widget("home.new.music", "w1", JsonObject(mapOf("title" to JsonPrimitive("Music")))),
            widget("home.new.clock", "w2"),
        )
        val migrated = Migrations.apply(8, graph)
        val kinds = migrated.walkInstances().map { it.kind.value }.toList()

        assertEquals(listOf("home.new.player.cover", "home.new.clock"), kinds)
        assertEquals(
            listOf("w1", "w2"),
            migrated.walkInstances().map { it.instanceId }.toList(),
            "the instance is re-pointed rather than replaced, so its id and its place are its own",
        )
    }

    @Test
    fun `the heading it used to carry is not smuggled into the successor`() {
        val graph = graphOf(widget("home.new.music", "w1", JsonObject(mapOf("title" to JsonPrimitive("Music")))))
        val props = Migrations.apply(8, graph).walkInstances().single().props
        assertTrue(props.isEmpty(), "the successor has no heading to put it in, so it starts at its defaults: $props")
    }

    @Test
    fun `a nested instance is migrated too`() {
        val child = SlotContent(widgets = listOf(widget("home.new.music", "inner")))
        val container = WidgetInstance(
            kind = WidgetKind("container.group"),
            instanceId = "outer",
            children = mapOf(SlotId("body") to child),
        )
        val migrated = Migrations.apply(8, graphOf(container))
        assertEquals(
            listOf("container.group", "home.new.player.cover"),
            migrated.walkInstances().map { it.kind.value }.toList(),
        )
    }

    @Test
    fun `a graph already at the current schema is returned untouched`() {
        val graph = graphOf(widget("home.new.music", "w1"))
        assertEquals(
            "home.new.music",
            Migrations.apply(LayoutReconcile.CURRENT_SCHEMA, graph).walkInstances().single().kind.value,
            "a file written by this build is not re-migrated, whatever it happens to contain",
        )
    }

    @Test
    fun `a structurally invalid version is refused rather than looped over`() {
        // A bogus negative version would otherwise spin the ladder about two
        // billion times and hang the launcher before its first frame.
        assertFailsWith<IllegalArgumentException> { Migrations.apply(0, graphOf()) }
        assertFailsWith<IllegalArgumentException> { Migrations.apply(Int.MIN_VALUE, graphOf()) }
    }
}
