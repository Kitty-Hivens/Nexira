package hivens.ui.layout

import hivens.widget.model.DefaultLayout
import hivens.widget.model.FamilyId
import hivens.widget.model.FamilyLayout
import hivens.widget.model.LAYOUT_SCHEMA
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayoutReconcileTest {

    private fun widget(kind: String, id: String) =
        WidgetInstance(WidgetKind(kind), id, JsonObject(emptyMap()))

    private fun navEntry(id: String, target: String) =
        WidgetInstance(WidgetKind("nav.entry"), id, JsonObject(mapOf("target" to JsonPrimitive(target))))

    private fun navTargetOf(w: WidgetInstance): String? = (w.props["target"] as? JsonPrimitive)?.content

    private fun surface(vararg slots: Pair<String, List<WidgetInstance>>) =
        SurfaceLayout(slots = slots.associate { (s, w) -> SlotId(s) to SlotContent(w) })

    private fun ok(result: LayoutReconcile.Result): LayoutGraph {
        assertIs<LayoutReconcile.Result.Ok>(result)
        return result.graph
    }

    @Test
    fun `the schema is one number, and the bundled default carries the same one`() {
        // A literal here is what let the two drift: the resource said 8 while the
        // build had moved to 9, and the loader read the stamp and discarded it, so
        // the mismatch was invisible until a step was added. Both sides now name
        // the same constant, and loading the bundle is the assertion, because
        // DefaultLayout refuses a resource stamped with anything else.
        assertEquals(LAYOUT_SCHEMA, LayoutReconcile.CURRENT_SCHEMA)
        DefaultLayout.load()
    }


    @Test
    fun `reconcile seeds default surfaces and slots the graph is missing`() {
        val user = LayoutGraph(surfaces = mapOf(SurfaceId("a") to surface("main" to emptyList())))
        val default = LayoutGraph(surfaces = mapOf(
            SurfaceId("a") to surface("main" to emptyList(), "added" to emptyList()),
            SurfaceId("b") to surface("only" to emptyList()),
        ))
        val out = ok(LayoutReconcile.reconcile(4, user, default))
        assertTrue(SurfaceId("b") in out.surfaces, "missing default surface must seed")
        assertTrue(SlotId("added") in out.surfaces[SurfaceId("a")]!!.slotsOf(FamilyId.GENERAL), "missing default slot must seed")
    }

    @Test
    fun `reconcile seeds a family the release added and leaves the reader's own alone`() {
        // A family is structural, exactly like a slot: the editor has no op that
        // creates or deletes one, so a family in the bundled default and absent from
        // the user's file is always an upstream addition. Without this the rail would
        // switch to a family that is not in the graph and draw nothing, with no way
        // back from inside the product.
        val project = FamilyId("projectView")
        val user = LayoutGraph(surfaces = mapOf(
            SurfaceId("rail") to SurfaceLayout(slots = mapOf(SlotId("news") to SlotContent(listOf(widget("k", "i1"))))),
        ))
        val default = LayoutGraph(surfaces = mapOf(
            SurfaceId("rail") to SurfaceLayout(families = mapOf(
                FamilyId.GENERAL to FamilyLayout(mapOf(SlotId("news") to SlotContent())),
                project to FamilyLayout(mapOf(SlotId("modData") to SlotContent())),
            )),
        ))

        val out = ok(LayoutReconcile.reconcile(LayoutReconcile.CURRENT_SCHEMA, user, default))
        val rail = out.surfaces[SurfaceId("rail")]!!

        assertTrue(project in rail.families, "a family added by the release must seed")
        assertTrue(SlotId("modData") in rail.slotsOf(project))
        // The reader's own arrangement in the family they already had is not
        // replaced by the default's empty one.
        assertEquals(listOf("i1"), rail.slotsOf(FamilyId.GENERAL)[SlotId("news")]!!.widgets.map { it.instanceId })
    }

    @Test
    fun `reconcile seeds a slot added inside a non-general family`() {
        val project = FamilyId("projectView")
        val user = LayoutGraph(surfaces = mapOf(
            SurfaceId("rail") to SurfaceLayout(families = mapOf(
                project to FamilyLayout(mapOf(SlotId("modData") to SlotContent())),
            )),
        ))
        val default = LayoutGraph(surfaces = mapOf(
            SurfaceId("rail") to SurfaceLayout(families = mapOf(
                project to FamilyLayout(mapOf(
                    SlotId("modData") to SlotContent(),
                    SlotId("authorData") to SlotContent(),
                )),
            )),
        ))

        val out = ok(LayoutReconcile.reconcile(LayoutReconcile.CURRENT_SCHEMA, user, default))
        assertTrue(SlotId("authorData") in out.surfaces[SurfaceId("rail")]!!.slotsOf(project))
    }

    @Test
    fun `reconcile leaves a current unique graph untouched when nothing to merge`() {
        val g = LayoutGraph(surfaces = mapOf(SurfaceId("a") to surface("main" to listOf(widget("k", "i1")))))
        assertEquals(g, ok(LayoutReconcile.reconcile(7, g, LayoutGraph.EMPTY)))
    }




    @Test
    fun `reconcile reports a pre-existing duplicate id`() {
        val g = LayoutGraph(surfaces = mapOf(
            SurfaceId("s") to surface("a" to listOf(widget("a", "dup")), "b" to listOf(widget("b", "dup"))),
        ))
        val result = LayoutReconcile.reconcile(4, g, LayoutGraph.EMPTY)
        assertIs<LayoutReconcile.Result.DuplicateId>(result)
        assertEquals("dup", result.id)
    }



    // ── v6 -> v7: home hero ──────────────────────────────────────────────────

    private fun v6HomeDefault(welcomeProps: JsonObject = JsonObject(emptyMap())) =
        LayoutGraph(surfaces = mapOf(
            SurfaceId("home.new") to surface("main" to listOf(
                WidgetInstance(WidgetKind("home.new.welcome"), "home-new-welcome-default", welcomeProps),
                widget("home.new.spacer", "home-new-spacer-default"),
                widget("home.new.recent", "home-new-recent-default"),
                widget("home.new.quicklaunch", "home-new-quicklaunch-default"),
            )),
        ))




}
