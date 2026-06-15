package hivens.launcher

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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LayoutReconcileTest {

    private fun widget(kind: String, id: String) =
        WidgetInstance(WidgetKind(kind), id, JsonObject(emptyMap()))

    private fun surface(vararg slots: Pair<String, List<WidgetInstance>>) =
        SurfaceLayout(slots = slots.associate { (s, w) -> SlotId(s) to SlotContent(w) })

    private fun ok(result: LayoutReconcile.Result): LayoutGraph {
        assertIs<LayoutReconcile.Result.Ok>(result)
        return result.graph
    }

    @Test
    fun `CURRENT_SCHEMA is the schema this build migrates up to`() {
        assertEquals(4, LayoutReconcile.CURRENT_SCHEMA)
    }

    @Test
    fun `reconcile migrates an older-schema graph -- v3 navbuttons expands to nav-entry`() {
        // The preset-from-an-older-schema case: a preset saved while the nav
        // rail was still the v3 navbuttons block must migrate, not load blank.
        val v3 = LayoutGraph(surfaces = mapOf(
            SurfaceId("appshell.leftrail") to surface("top" to listOf(widget("appshell.leftrail.navbuttons", "nb"))),
        ))
        val out = ok(LayoutReconcile.reconcile(3, v3, LayoutGraph.EMPTY))
        val top = out.surfaces[SurfaceId("appshell.leftrail")]!!.slots[SlotId("top")]!!.widgets
        assertEquals(List(6) { "nav.entry" }, top.map { it.kind.value })
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
        assertEquals(g, ok(LayoutReconcile.reconcile(4, g, LayoutGraph.EMPTY)))
    }

    @Test
    fun `reconcile reports a migration-minted collision`() {
        // navbuttons "nb" mints nb-home/...; a pre-existing "nb-home" collides.
        val v3 = LayoutGraph(surfaces = mapOf(
            SurfaceId("appshell.leftrail") to surface("top" to listOf(widget("appshell.leftrail.navbuttons", "nb"))),
            SurfaceId("home.new") to surface("main" to listOf(widget("home.new.clock", "nb-home"))),
        ))
        val result = LayoutReconcile.reconcile(3, v3, LayoutGraph.EMPTY)
        assertIs<LayoutReconcile.Result.DuplicateId>(result)
        assertEquals("nb-home", result.id)
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
}
