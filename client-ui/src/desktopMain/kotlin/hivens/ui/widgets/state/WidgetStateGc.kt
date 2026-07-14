package hivens.ui.widgets.state

import hivens.ui.layout.LayoutGraphRepository
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Prunes orphaned per-instance state. Keyed reactively off the layout graph rather
 * than imperatively at the editor's remove/reset calls: those calls are async, and
 * -- more importantly -- instances also disappear BELOW the editor (load-time schema
 * migrations, the reconcile unknown-kind prune, duplicate-id fallback). Every one of
 * those re-emits the graph, so a single collector that retains state for the current
 * live instanceIds covers remove, resetSurface, resetAll, the startup sweep, and the
 * load-time prunes in one place.
 *
 * Debounced: [LayoutGraphRepository.observe] re-emits per drag frame (geometry); the
 * orphan set only ever shrinks on a destroy, none of which is latency-sensitive.
 * Runs on the app scope so GC happens even when no surface hosting a stateful widget
 * is currently composed.
 */
@OptIn(FlowPreview::class)
class WidgetStateGc(
    repo: LayoutGraphRepository,
    store: WidgetStateStore,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            repo.observe().debounce(GC_DEBOUNCE_MS).collect { graph ->
                store.retain(graph.walkInstances().map { it.instanceId }.toSet())
            }
        }
    }

    private companion object {
        const val GC_DEBOUNCE_MS = 1_000L
    }
}
