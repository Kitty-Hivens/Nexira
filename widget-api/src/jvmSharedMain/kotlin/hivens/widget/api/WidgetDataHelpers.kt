package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.flow.StateFlow

/** Wraps an existing [StateFlow] (e.g. a service field) as a [WidgetDataSource]. */
fun <T> flowSource(flow: StateFlow<T>): WidgetDataSource<T> =
    object : WidgetDataSource<T> {
        override val state: StateFlow<T> = flow
    }

/**
 * Binds a widget to a source by [key] and tracks it as Compose [State]: the
 * widget declares what data it wants, not where it comes from. The lookup is
 * memoized on the id; the value updates as the source's [StateFlow] emits.
 *
 * Errors if the key is unregistered -- a source is app-static, so a miss is a
 * wiring bug, not a runtime state (unlike `useService`, where null is normal
 * because providers mount/unmount).
 */
@Composable
fun <T> rememberSource(key: SourceKey<T>): State<T> {
    val registry = LocalWidgetDataRegistry.current
    val source = remember(key.id) { registry.get(key) }
    return source.state.collectAsState()
}

// Non-Composable accessors for the rule-engine / headless consumers: a
// synchronous current value and the raw flow, no recomposer required.
fun <T> WidgetDataRegistry.current(key: SourceKey<T>): T = get(key).state.value
fun <T> WidgetDataRegistry.flow(key: SourceKey<T>): StateFlow<T> = get(key).state
