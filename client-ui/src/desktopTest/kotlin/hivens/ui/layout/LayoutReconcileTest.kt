package hivens.ui.layout

import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SlotOrientation
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
    fun `CURRENT_SCHEMA is the schema this build migrates up to`() {
        assertEquals(8, LayoutReconcile.CURRENT_SCHEMA)
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
        assertTrue(SlotId("added") in out.surfaces[SurfaceId("a")]!!.slots, "missing default slot must seed")
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
