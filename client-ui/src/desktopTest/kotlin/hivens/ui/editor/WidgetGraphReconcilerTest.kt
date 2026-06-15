package hivens.ui.editor

import androidx.compose.runtime.Composable
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetGraphReconcilerTest {

    private class FakeDescriptor(
        override val kind: WidgetKind,
        override val slots: List<SlotId>,
    ) : WidgetDescriptor {
        override val displayName = kind.value
        override val removable = true
        @Composable override fun Render(instance: WidgetInstance) {}
    }

    private class FakeRegistry(descriptors: List<FakeDescriptor>) : WidgetRegistry {
        private val map = descriptors.associateBy { it.kind }
        override fun all(): Map<WidgetKind, WidgetDescriptor> = map
        override fun get(kind: WidgetKind): WidgetDescriptor? = map[kind]
    }

    private fun registryOf(vararg pairs: Pair<String, List<String>>) =
        FakeRegistry(pairs.map { (k, slots) -> FakeDescriptor(WidgetKind(k), slots.map(::SlotId)) })

    private fun graphWith(vararg widgets: WidgetInstance) =
        LayoutGraph(surfaces = mapOf(
            SurfaceId("s") to SurfaceLayout(slots = mapOf(SlotId("main") to SlotContent(widgets.toList()))),
        ))

    private fun LayoutGraph.slotWidgets() =
        surfaces[SurfaceId("s")]!!.slots[SlotId("main")]!!.widgets

    private fun LayoutGraph.firstWidget() = slotWidgets().first()

    @Test
    fun `seeds a declared child slot missing from a container`() {
        val container = WidgetInstance(WidgetKind("container"), "c1") // children == emptyMap
        val result = WidgetGraphReconciler.reconcile(
            graphWith(container),
            registryOf("container" to listOf("body")),
        )
        val out = result.graph.firstWidget()
        assertEquals(setOf(SlotId("body")), out.children.keys)
        assertEquals(SlotContent(), out.children[SlotId("body")])
        assertEquals(1, result.seededSlots)
    }

    @Test
    fun `is idempotent -- a container that already has its slot is untouched`() {
        val container = WidgetInstance(
            WidgetKind("container"), "c1",
            children = mapOf(SlotId("body") to SlotContent()),
        )
        val g = graphWith(container)
        val result = WidgetGraphReconciler.reconcile(g, registryOf("container" to listOf("body")))
        assertEquals(g, result.graph)
        assertEquals(0, result.seededSlots)
    }

    @Test
    fun `seeds a newly-declared sibling slot without disturbing existing child content`() {
        val child = WidgetInstance(WidgetKind("leaf"), "x")
        val container = WidgetInstance(
            WidgetKind("container"), "c1",
            children = mapOf(SlotId("a") to SlotContent(listOf(child))),
        )
        val out = WidgetGraphReconciler.reconcile(
            graphWith(container),
            registryOf("container" to listOf("a", "b"), "leaf" to emptyList()),
        ).graph.firstWidget()
        assertEquals(listOf("x"), out.children[SlotId("a")]!!.widgets.map { it.instanceId })
        assertEquals(SlotContent(), out.children[SlotId("b")])
    }

    @Test
    fun `keeps an unknown kind when not pruning (no schema bump)`() {
        // Without a schema bump the data is preserved verbatim; the placeholder
        // surfaces it at render time.
        val g = graphWith(WidgetInstance(WidgetKind("gone"), "g1"))
        val result = WidgetGraphReconciler.reconcile(g, registryOf(), prune = false)
        assertEquals(g, result.graph)
        assertEquals(0, result.prunedWidgets)
    }

    @Test
    fun `prune drops an unknown kind absent from registry and default on a bump`() {
        val g = graphWith(WidgetInstance(WidgetKind("gone"), "g1"), WidgetInstance(WidgetKind("known"), "k1"))
        val result = WidgetGraphReconciler.reconcile(
            g, registryOf("known" to emptyList()), defaultKinds = emptySet(), prune = true,
        )
        assertEquals(listOf("k1"), result.graph.slotWidgets().map { it.instanceId })
        assertEquals(1, result.prunedWidgets)
    }

    @Test
    fun `prune keeps an unknown kind the bundled default still declares`() {
        val g = graphWith(WidgetInstance(WidgetKind("defaultonly"), "d1"))
        val result = WidgetGraphReconciler.reconcile(
            g, registryOf(), defaultKinds = setOf(WidgetKind("defaultonly")), prune = true,
        )
        assertEquals(listOf("d1"), result.graph.slotWidgets().map { it.instanceId })
        assertEquals(0, result.prunedWidgets)
    }
}
