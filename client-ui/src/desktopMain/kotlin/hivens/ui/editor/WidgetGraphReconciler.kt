package hivens.ui.editor

import hivens.widget.api.WidgetRegistry
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotContent
import hivens.widget.model.WidgetKind
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
 * #333 -- removed/renamed kinds: a widget whose kind is absent from the registry
 * renders nothing (SlotRenderer skips it) while its props/children/placement
 * persist on disk forever. Unknown kinds are kept by default (the editor paints
 * an "unsupported widget" placeholder so the user can see and remove them) -- the
 * data is preserved. Only when [prune] (an actual schema bump just happened) does
 * the pass DROP instances whose kind is absent from BOTH the registry and
 * [defaultKinds]: a deliberate app update is the safe moment to reap kinds a
 * rename/removal orphaned. A future plugin runtime registers plugin kinds into
 * the registry, so a loaded plugin's kinds are "known" and preserved.
 *
 * The pass is idempotent: a graph already in good shape reconciles to an equal
 * graph and the caller writes nothing.
 */
object WidgetGraphReconciler {

    data class Result(val graph: LayoutGraph, val seededSlots: Int, val prunedWidgets: Int)

    fun reconcile(
        graph: LayoutGraph,
        registry: WidgetRegistry,
        defaultKinds: Set<WidgetKind> = emptySet(),
        prune: Boolean = false,
    ): Result {
        var seededSlots = 0
        var prunedWidgets = 0
        val out = graph.flatMapInstances { widget ->
            val descriptor = registry[widget.kind]
            when {
                descriptor != null -> {
                    val missing = descriptor.slots.filter { it !in widget.children }
                    if (missing.isEmpty()) {
                        listOf(widget)
                    } else {
                        seededSlots += missing.size
                        listOf(widget.copy(children = widget.children + missing.associateWith { SlotContent() }))
                    }
                }
                // Unknown kind, schema just bumped, and not a bundled-default kind
                // either: nothing renders it and nothing reintroduces it -- drop.
                prune && widget.kind !in defaultKinds -> {
                    prunedWidgets++
                    emptyList()
                }
                // Unknown but preserved: kept on disk, placeholder shows in edit mode.
                else -> listOf(widget)
            }
        }
        return Result(out, seededSlots, prunedWidgets)
    }
}
