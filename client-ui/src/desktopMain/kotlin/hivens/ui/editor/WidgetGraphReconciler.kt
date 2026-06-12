package hivens.ui.editor

import hivens.widget.api.WidgetRegistry
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.flatMapInstances

/**
 * Registry-aware reconciliation the launcher cannot do: [LayoutGraphRepository]
 * seeds missing bundled-default surfaces and slots, but lives in `client-launcher`
 * and has no [WidgetRegistry], so anything keyed on a widget's DESCRIPTOR is
 * resolved here, once, at startup against the loaded graph.
 *
 * #331 -- declared container child slots: a container persisted before child-slot
 * seeding existed (or whose descriptor gained a slot in a later release) ships
 * with [WidgetInstance.children] missing a declared slot. The model's
 * `mutateNested` intentionally rejects undeclared slots (no auto-create -- typo
 * protection stays at the model boundary), so a drop into that slot silently
 * no-ops while the empty-slot placeholder still registers bounds. Seeding the
 * descriptor's child slots here makes nested drops land.
 *
 * The pass is idempotent: a graph already in good shape reconciles to an equal
 * graph and the caller writes nothing.
 */
object WidgetGraphReconciler {

    data class Result(val graph: LayoutGraph, val seededSlots: Int)

    fun reconcile(graph: LayoutGraph, registry: WidgetRegistry): Result {
        var seededSlots = 0
        val out = graph.flatMapInstances { widget ->
            val descriptor = registry[widget.kind] ?: return@flatMapInstances listOf(widget)
            val missing = descriptor.slots.filter { it !in widget.children }
            if (missing.isEmpty()) return@flatMapInstances listOf(widget)
            seededSlots += missing.size
            listOf(widget.copy(children = widget.children + missing.associateWith { SlotContent() }))
        }
        return Result(out, seededSlots)
    }
}
