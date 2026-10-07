package hivens.ui.widgets.state

import hivens.ui.layout.LayoutGraphRepository
import hivens.widget.model.walkInstances
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

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
 *
 * Live is not the whole of what is kept. [alsoReferenced] names the ids something
 * else will bring back, the saved presets: loading one replaces the graph, and an id
 * the replacement does not carry is not gone for good if another preset still has it.
 *
 * Nothing is pruned while the layout is read-only. The graph is then the bundled
 * default standing in for a file this build will not touch, and the state of every
 * widget in that file would be swept as orphaned: going back to the build that can
 * read it brought the arrangement back with every note and list empty.
 */
@OptIn(FlowPreview::class)
class WidgetStateGc(
    repo: LayoutGraphRepository,
    store: WidgetStateStore,
    scope: CoroutineScope,
    alsoReferenced: () -> Set<String> = { emptySet() },
) {
    init {
        scope.launch {
            repo.observe().debounce(GC_DEBOUNCE_MS.milliseconds).collect { graph ->
                if (repo.isReadOnly) return@collect
                store.retain(graph.walkInstances().map { it.instanceId }.toSet() + alsoReferenced())
            }
        }
    }

    private companion object {
        const val GC_DEBOUNCE_MS = 1_000L
    }
}
