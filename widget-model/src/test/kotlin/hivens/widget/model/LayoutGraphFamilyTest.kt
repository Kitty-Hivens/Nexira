package hivens.widget.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A surface's families, which are alternative sets of slots and not alternative
 * arrangements of one set.
 *
 * The property that matters throughout is that the graph holds them all at once
 * and switching between them writes nothing. Everything else here is a
 * consequence of that: a transform has to be able to reach one family without
 * disturbing the other, and every sweep that claims to walk the graph has to walk
 * all of them, or a widget goes unseen until the day someone opens the family it
 * lives in.
 */
class LayoutGraphFamilyTest {

    private val rail = SurfaceId("appshell.rightrail")
    private val project = FamilyId("projectView")
    private val news = SlotId("news")
    private val modData = SlotId("modData")

    private val feed = WidgetInstance(WidgetKind("news.feed"), "feed-1", JsonObject(emptyMap()))
    private val body = WidgetInstance(WidgetKind("mod.body"), "body-1", JsonObject(emptyMap()))
    private val extra = WidgetInstance(WidgetKind("x"), "x-1", JsonObject(emptyMap()))

    private fun graph(): LayoutGraph = LayoutGraph(
        surfaces = mapOf(
            rail to SurfaceLayout(
                families = mapOf(
                    FamilyId.GENERAL to FamilyLayout(mapOf(news to SlotContent(listOf(feed)))),
                    project to FamilyLayout(mapOf(modData to SlotContent(listOf(body)))),
                ),
            ),
        ),
    )

    private val generalNews = SlotPath(rail, news)
    private val projectMod = SlotPath(rail, modData, family = project)

    // ── The address ──────────────────────────────────────────────────────

    @Test
    fun `a path that names no family means the general one`() {
        assertEquals(FamilyId.GENERAL, SlotPath(rail, news).family)
    }

    @Test
    fun `two families can hold the same slot name and it is not the same slot`() {
        val g = LayoutGraph(
            surfaces = mapOf(
                rail to SurfaceLayout(
                    families = mapOf(
                        FamilyId.GENERAL to FamilyLayout(mapOf(news to SlotContent(listOf(feed)))),
                        project to FamilyLayout(mapOf(news to SlotContent(listOf(body)))),
                    ),
                ),
            ),
        )
        assertEquals(listOf(feed), g.traverse(SlotPath(rail, news))?.widgets)
        assertEquals(listOf(body), g.traverse(SlotPath(rail, news, family = project))?.widgets)
    }

    @Test
    fun `a path into a family the surface does not have reads nothing rather than throwing`() {
        assertNull(graph().traverse(SlotPath(rail, news, family = FamilyId("never-declared"))))
    }

    @Test
    fun `a mutation on a family the surface does not have is identity`() {
        val before = graph()
        val after = before.insertWidget(SlotPath(rail, news, family = FamilyId("nope")), extra, 0)
        assertSame(before, after)
    }

    // ── Isolation ────────────────────────────────────────────────────────

    @Test
    fun `editing one family leaves the other exactly as it was`() {
        val before = graph()
        val after = before.insertWidget(projectMod, extra, 0)

        assertEquals(listOf(extra, body), after.traverse(projectMod)?.widgets)
        // Reference identity, not just equality: the general family was not
        // rebuilt, so nothing about it can have been quietly renormalised.
        assertSame(
            before.surfaces[rail]!!.family(FamilyId.GENERAL),
            after.surfaces[rail]!!.family(FamilyId.GENERAL),
        )
    }

    @Test
    fun `a no-op on one family does not rebuild the graph`() {
        val before = graph()
        assertSame(before, before.removeWidget(projectMod, "not-here"))
    }

    @Test
    fun `slot mode is per family`() {
        val out = graph().setFlow(projectMod, FlowSpec.Row)
        assertEquals(FlowSpec.Row, out.traverse(projectMod)?.flow)
        assertEquals(FlowSpec.Column, out.traverse(generalNews)?.flow)
    }

    // ── Sweeps that must not miss a family ───────────────────────────────

    @Test
    fun `walkInstances reaches a widget that only a non-general family holds`() {
        assertEquals(
            setOf("feed-1", "body-1"),
            graph().walkInstances().map { it.instanceId }.toSet(),
        )
    }

    @Test
    fun `instanceIds covers every family on the surface`() {
        assertEquals(setOf("feed-1", "body-1"), graph().surfaces[rail]!!.instanceIds())
    }

    @Test
    fun `removeInstanceIds reaches into every family`() {
        val out = graph().surfaces[rail]!!.removeInstanceIds(setOf("body-1"))
        assertTrue(out.slotsOf(project)[modData]!!.widgets.isEmpty())
        assertEquals(listOf(feed), out.slotsOf(FamilyId.GENERAL)[news]!!.widgets)
    }

    @Test
    fun `flatMapInstances rewrites in every family`() {
        val out = graph().flatMapInstances { listOf(it.copy(kind = WidgetKind("rewritten"))) }
        assertEquals(
            listOf("rewritten", "rewritten"),
            out.walkInstances().map { it.kind.value }.toList(),
        )
    }

    @Test
    fun `resetSurface clears ids that leaked into another surface's non-general family`() {
        // The id the default is about to restore is sitting in a second surface's
        // project view, put there by a cross-surface move. Left alone it collides
        // with the restored copy and the tree-wide uniqueness check rejects the
        // whole reset, which traps the reader on the surface they asked to undo.
        val other = SurfaceId("library")
        val g = LayoutGraph(
            surfaces = mapOf(
                rail to SurfaceLayout(families = mapOf(FamilyId.GENERAL to FamilyLayout())),
                other to SurfaceLayout(
                    families = mapOf(project to FamilyLayout(mapOf(modData to SlotContent(listOf(feed))))),
                ),
            ),
        )
        val restored = SurfaceLayout(slots = mapOf(news to SlotContent(listOf(feed))))

        val out = g.resetSurface(rail, restored)

        assertEquals(listOf(feed), out.traverse(generalNews)?.widgets)
        assertTrue(out.surfaces[other]!!.slotsOf(project)[modData]!!.widgets.isEmpty())
    }

    // ── Adding and styling a family ──────────────────────────────────────

    @Test
    fun `ensureFamily adds an absent one and is identity for a present one`() {
        val before = graph()
        assertSame(before, before.ensureFamily(rail, project))

        val added = before.ensureFamily(rail, FamilyId("authorView"))
        assertEquals(
            setOf("general", "projectView", "authorView"),
            added.surfaces[rail]!!.families.keys.map { it.value }.toSet(),
        )
        assertTrue(added.surfaces[rail]!!.slotsOf(FamilyId("authorView")).isEmpty())
    }

    @Test
    fun `ensureFamily on a surface nothing has declared creates it`() {
        val out = LayoutGraph.EMPTY.ensureFamily(rail, project)
        assertEquals(listOf(project), out.surfaces[rail]!!.families.keys.toList())
    }

    @Test
    fun `a family carries its own plane, and an all-default one is not written down`() {
        val plane = SurfaceSpec(opacity = 0.4f)
        val styled = graph().updateFamilySurface(rail, project, plane)

        assertEquals(plane, styled.surfaces[rail]!!.family(project)!!.surface)
        // The general family was not styled, so it stays unstyled.
        assertNull(styled.surfaces[rail]!!.family(FamilyId.GENERAL)!!.surface)

        // A spec that says nothing is absence, matching the per-widget rule, so a
        // family the reader never touched does not write out a record of defaults.
        val cleared = styled.updateFamilySurface(rail, project, SurfaceSpec())
        assertNull(cleared.surfaces[rail]!!.family(project)!!.surface)
        assertSame(cleared, cleared.updateFamilySurface(rail, project, null))
    }

    @Test
    fun `styling a family the surface does not have is identity`() {
        val before = graph()
        assertSame(before, before.updateFamilySurface(rail, FamilyId("nope"), SurfaceSpec(opacity = 0.2f)))
    }

    // ── Round trip ───────────────────────────────────────────────────────

    @Test
    fun `props written into one family stay there across a rebuild`() {
        val props = buildJsonObject { put("title", "Sodium") }
        val out = graph().updateWidgetProps(projectMod, "body-1", props)

        assertEquals(props, out.traverse(projectMod)!!.widgets.single().props)
        assertEquals(JsonObject(emptyMap()), out.traverse(generalNews)!!.widgets.single().props)
    }
}
