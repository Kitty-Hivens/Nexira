package hivens.widget.model

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultLayoutTest {

    @Test
    fun `bundled default decodes to the kernel-3 + B-series surface set`() {
        val graph = DefaultLayout.load()
        val surfaceIds = graph.surfaces.keys.map { it.value }.toSet()
        assertEquals(
            setOf(
                // shell-as-surface root (Column of top bar + body) and its nested
                // body (the three region widgets in a Row) + the title-bar surface
                "appshell.root",
                "appshell.body",
                "appshell.topbar",
                // kernel-3 originals
                "home.classic", "home.new", "library",
                "appshell.leftrail", "appshell.rightrail",
                // Phase B.1 widgetized screens (incremental landing)
                "about",
                "bg.settings",
                "customization",
                "profile",
                "server.details",
                "theme.picker",
            ),
            surfaceIds,
            "default-layout drift -- expected exactly these surfaces",
        )
    }

    @Test
    fun `every surface declares at least one slot with at least one widget`() {
        val graph = DefaultLayout.load()
        graph.surfaces.forEach { (surfaceId, layout) ->
            assertTrue(
                layout.slots.isNotEmpty(),
                "surface ${surfaceId.value} declares no slots; SlotRenderer would render nothing",
            )
            layout.slots.forEach { (slotId, content) ->
                assertTrue(
                    content.widgets.isNotEmpty(),
                    "slot ${surfaceId.value}.${slotId.value} is empty; kernel-3 populates every slot",
                )
            }
        }
    }

    @Test
    fun `every widget instance has a non-blank kind and unique instance_id`() {
        val graph = DefaultLayout.load()
        val instanceIds = mutableListOf<String>()
        graph.surfaces.values.forEach { layout ->
            layout.slots.values.forEach { content ->
                content.widgets.forEach { widget ->
                    assertTrue(widget.kind.value.isNotBlank(), "blank widget kind")
                    assertTrue(widget.instanceId.isNotBlank(), "blank instance_id on ${widget.kind.value}")
                    instanceIds += widget.instanceId
                }
            }
        }
        val dupes = instanceIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet(), dupes, "instance_id values must be unique across the graph")
    }

    @Test
    fun `kernel surface and slot identifiers match the documented map`() {
        val graph = DefaultLayout.load()
        fun slots(surface: String) = graph.surfaces[SurfaceId(surface)]?.slots?.keys?.map { it.value }?.toSet()
            ?: emptySet()

        assertEquals(setOf("main"),           slots("home.classic"))
        assertEquals(setOf("main"),           slots("home.new"))
        assertEquals(setOf("header", "body"), slots("library"))
        assertEquals(setOf("top", "bottom"),  slots("appshell.leftrail"))
        assertEquals(setOf("news", "bottom"),  slots("appshell.rightrail"))
    }

    @Test
    fun `appshell leftrail is a unified nav-entry rail in declared order`() {
        val graph = DefaultLayout.load()
        val leftrail = graph.surfaces[SurfaceId("appshell.leftrail")]!!.slots
        fun targets(slot: String) = leftrail[SlotId(slot)]!!.widgets.map {
            assertEquals("nav.entry", it.kind.value, "leftrail items must all be nav.entry")
            it.props["target"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("Home", "Library", "Browse", "Profile", "Wardrobe", "Settings", "About"), targets("top"))
        assertEquals(listOf("Console", "Logout"), targets("bottom"))
    }
}
