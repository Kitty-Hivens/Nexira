package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultLayoutTest {

    /**
     * The bundled file is read leniently, because a file on a user's disk may carry
     * keys a newer build wrote. That tolerance has no place here: this one ships in
     * the jar, so a key nothing reads is dead weight rather than forward
     * compatibility.
     *
     * It went unread once. A per-instance frame -- a corner and three insets on the
     * right panel -- outlived the record that carried it, and the panel quietly lost
     * both while the JSON still described them.
     */
    @Test
    fun `the bundled layout carries no key the model does not read`() {
        DefaultLayout.load(Json)
    }

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
                // Floats over the content column rather than taking space in it;
                // carries the activity pill.
                "appshell.overlay",
                // kernel-3 originals
                "home.classic", "home.new", "library",
                "appshell.leftrail", "appshell.rightrail",
                // Phase B.1 widgetized screens (incremental landing)
                "about",
                "bg.settings",
                "profile",
                "server.details",
                "theme.picker",
            ),
            surfaceIds,
            "default-layout drift -- expected exactly these surfaces",
        )
    }

    /**
     * The general family is what a surface shows at rest, so it is the one that
     * has to be furnished: a surface arriving empty is a blank pane on first run.
     *
     * A family the app only enters in some state is held to a weaker rule -- it
     * must declare its slots, because slots are structural and the reader cannot
     * add one, but it may declare them empty. What goes in them is the business of
     * the code that switches to it, and seeding a placeholder there would put a
     * widget in front of the reader that nothing asked for.
     */
    @Test
    fun `every surface furnishes its general family and declares slots in the rest`() {
        val graph = DefaultLayout.load()
        graph.surfaces.forEach { (surfaceId, layout) ->
            assertTrue(
                layout.slotsOf(FamilyId.GENERAL).isNotEmpty(),
                "surface ${surfaceId.value} declares no general family; SlotRenderer would render nothing",
            )
            layout.slotsOf(FamilyId.GENERAL).forEach { (slotId, content) ->
                assertTrue(
                    content.widgets.isNotEmpty(),
                    "slot ${surfaceId.value}.${slotId.value} is empty; kernel-3 populates every slot",
                )
            }
            layout.families.forEach { (familyId, family) ->
                assertTrue(
                    family.slots.isNotEmpty(),
                    "family ${surfaceId.value}/${familyId.value} declares no slots; it would render nothing at all",
                )
            }
        }
    }

    @Test
    fun `every widget instance has a non-blank kind and unique instance_id`() {
        val graph = DefaultLayout.load()
        val instanceIds = mutableListOf<String>()
        // Every family. An id has to be unique across the whole file, not across the
        // one family that happens to be showing: the uniqueness sweep on load walks
        // all of them and rejects the graph, so a collision hidden in a family nobody
        // has opened yet takes the layout down the first time someone does.
        graph.surfaces.values.forEach { layout ->
            layout.allSlots().forEach { content ->
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
        fun slots(surface: String) = graph.surfaces[SurfaceId(surface)]?.slotsOf(FamilyId.GENERAL)?.keys?.map { it.value }?.toSet()
            ?: emptySet()

        assertEquals(setOf("main"),           slots("home.classic"))
        assertEquals(setOf("main"),           slots("home.new"))
        assertEquals(setOf("header", "body"), slots("library"))
        assertEquals(setOf("top", "bottom"),  slots("appshell.leftrail"))
        assertEquals(setOf("news", "bottom"),  slots("appshell.rightrail"))
    }

    /**
     * The rail is the one surface with a second family, and [hivens.ui.RightPanel]
     * branches on the name to pick which slots it lays out. A family renamed here
     * and not there does not fail to compile; it renders the rail's fallback and
     * the reader's project-view arrangement goes quiet, so the pair is pinned.
     */
    @Test
    fun `the right rail declares a general family and a project view`() {
        val graph = DefaultLayout.load()
        val rail = graph.surfaces[SurfaceId("appshell.rightrail")]!!
        assertEquals(setOf("general", "projectView"), rail.families.keys.map { it.value }.toSet())
        assertEquals(
            setOf("modData", "authorData"),
            rail.slotsOf(FamilyId("projectView")).keys.map { it.value }.toSet(),
        )
    }

    @Test
    fun `appshell leftrail is a unified nav-entry rail in declared order`() {
        val graph = DefaultLayout.load()
        val leftrail = graph.surfaces[SurfaceId("appshell.leftrail")]!!.slotsOf(FamilyId.GENERAL)
        fun targets(slot: String) = leftrail[SlotId(slot)]!!.widgets.map {
            assertEquals("nav.entry", it.kind.value, "leftrail items must all be nav.entry")
            it.props["target"]?.jsonPrimitive?.content
        }
        assertEquals(listOf("Home", "Library", "Browse", "Profile", "Wardrobe", "Settings", "About"), targets("top"))
        assertEquals(listOf("Console", "Logout"), targets("bottom"))
    }
}
