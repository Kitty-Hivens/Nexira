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
        assertEquals(7, LayoutReconcile.CURRENT_SCHEMA)
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
        // v3 navbuttons -> six entries (v3->v4); then v4->v5 inserts Wardrobe after Profile.
        assertEquals(
            listOf("Home", "Library", "Browse", "Profile", "Wardrobe", "Settings", "About"),
            top.map { navTargetOf(it) },
        )
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
    fun `reconcile v5 to v6 relocates the shell regions under a top bar`() {
        // The high-risk migration: the reconciler does not reshape an existing
        // slot, so without the step every upgrading user keeps the old Row and
        // never sees the bar. A customized region (widthDp 80) must survive.
        val left = WidgetInstance(
            WidgetKind("appshell.region.left"), "L", JsonObject(mapOf("widthDp" to JsonPrimitive(80))),
        )
        val v5 = LayoutGraph(surfaces = mapOf(
            SurfaceId("appshell.root") to SurfaceLayout(slots = mapOf(
                SlotId("regions") to SlotContent(
                    widgets = listOf(left, widget("appshell.region.center", "C"), widget("appshell.region.right", "R")),
                    orientation = SlotOrientation.Row,
                ),
            )),
        ))
        val out = ok(LayoutReconcile.reconcile(5, v5, LayoutGraph.EMPTY))

        val regions = out.surfaces[SurfaceId("appshell.root")]!!.slots[SlotId("regions")]!!
        assertEquals(SlotOrientation.Column, regions.orientation)
        assertEquals(listOf("appshell.region.top", "appshell.region.body"), regions.widgets.map { it.kind.value })

        val body = out.surfaces[SurfaceId("appshell.body")]!!.slots[SlotId("content")]!!
        assertEquals(SlotOrientation.Row, body.orientation)
        assertEquals(listOf("L", "C", "R"), body.widgets.map { it.instanceId }, "regions moved, none dropped")
        assertEquals(JsonPrimitive(80), body.widgets.first().props["widthDp"], "custom props preserved")
    }

    @Test
    fun `reconcile v5 to v6 is idempotent when the top region already exists`() {
        val already = LayoutGraph(surfaces = mapOf(
            SurfaceId("appshell.root") to SurfaceLayout(slots = mapOf(
                SlotId("regions") to SlotContent(
                    widgets = listOf(widget("appshell.region.top", "T"), widget("appshell.region.body", "B")),
                    orientation = SlotOrientation.Column,
                ),
            )),
        ))
        // Run the step (from < 6) against an already-migrated graph: the guard
        // must leave it untouched rather than double-wrap.
        assertEquals(already, ok(LayoutReconcile.reconcile(5, already, LayoutGraph.EMPTY)))
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

    @Test
    fun `reconcile v4 to v5 inserts the Wardrobe nav entry after Profile`() {
        val rail = LayoutGraph(surfaces = mapOf(
            SurfaceId("appshell.leftrail") to surface("top" to listOf(
                navEntry("appshell-leftrail-nav-profile", "Profile"),
                navEntry("appshell-leftrail-nav-settings", "Settings"),
            )),
        ))
        val top = ok(LayoutReconcile.reconcile(4, rail, LayoutGraph.EMPTY))
            .surfaces[SurfaceId("appshell.leftrail")]!!.slots[SlotId("top")]!!.widgets
        assertEquals(listOf("Profile", "Wardrobe", "Settings"), top.map { navTargetOf(it) })
    }

    @Test
    fun `reconcile v4 to v5 does not double-insert an existing Wardrobe entry`() {
        val rail = LayoutGraph(surfaces = mapOf(
            SurfaceId("appshell.leftrail") to surface("top" to listOf(
                navEntry("p", "Profile"),
                navEntry("w", "Wardrobe"),
            )),
        ))
        val top = ok(LayoutReconcile.reconcile(4, rail, LayoutGraph.EMPTY))
            .surfaces[SurfaceId("appshell.leftrail")]!!.slots[SlotId("top")]!!.widgets
        assertEquals(1, top.count { navTargetOf(it) == "Wardrobe" })
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

    @Test
    fun `reconcile v6 to v7 swaps the untouched home default onto the hero`() {
        val out = ok(LayoutReconcile.reconcile(6, v6HomeDefault(), LayoutGraph.EMPTY))
        val main = out.surfaces[SurfaceId("home.new")]!!.slots[SlotId("main")]!!.widgets
        assertEquals(
            listOf("home.new.welcome", "home.new.spacer", "home.new.hero", "home.new.recent"),
            main.map { it.kind.value },
        )
        assertEquals("home-new-hero-default", main[2].instanceId)
        assertEquals(JsonPrimitive(false), main.first().props["showSubtitle"], "onboarding line off by default")
    }

    @Test
    fun `reconcile v6 to v7 keeps a user-set welcome subtitle choice`() {
        val userProps = JsonObject(mapOf(
            "showSubtitle" to JsonPrimitive(true),
            "customGreeting" to JsonPrimitive("yo"),
        ))
        val out = ok(LayoutReconcile.reconcile(6, v6HomeDefault(userProps), LayoutGraph.EMPTY))
        val welcome = out.surfaces[SurfaceId("home.new")]!!.slots[SlotId("main")]!!.widgets.first()
        assertEquals(JsonPrimitive(true), welcome.props["showSubtitle"], "explicit user choice survives")
        assertEquals(JsonPrimitive("yo"), welcome.props["customGreeting"], "other props carry over")
    }

    @Test
    fun `reconcile v6 to v7 leaves a customised home slot alone`() {
        // Any deviation from the bundled instance-id order means the user
        // arranged the surface -- the migration must not undo their work.
        val custom = LayoutGraph(surfaces = mapOf(
            SurfaceId("home.new") to surface("main" to listOf(
                widget("home.new.quicklaunch", "home-new-quicklaunch-default"),
                widget("home.new.clock", "my-clock"),
            )),
        ))
        assertEquals(custom, ok(LayoutReconcile.reconcile(6, custom, LayoutGraph.EMPTY)))
    }

    @Test
    fun `reconcile v6 to v7 is a no-op on an already-migrated graph`() {
        val migrated = ok(LayoutReconcile.reconcile(6, v6HomeDefault(), LayoutGraph.EMPTY))
        assertEquals(migrated, ok(LayoutReconcile.reconcile(6, migrated, LayoutGraph.EMPTY)))
    }
}
