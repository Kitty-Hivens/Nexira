package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.flow.StateFlow

/** Wraps an existing [StateFlow] (e.g. a service field) as a [WidgetDataSource]. */
fun <T> flowSource(flow: StateFlow<T>): WidgetDataSource<T> =
    object : WidgetDataSource<T> {
        override val state: StateFlow<T> = flow
    }

/**
 * Binds a widget to an app-level source by [key] and tracks it as Compose
 * [State]: the widget declares what data it wants, not where it comes from. The
 * value updates as the source's [StateFlow] emits.
 *
 * Errors if the key is unregistered. An app source is registered at startup, so a
 * miss is a wiring bug, not a runtime state (unlike `useService`, where null is
 * normal because providers mount/unmount).
 *
 * App keys only. A key of another level can be missing where a widget stands, and
 * [rememberScopedSource] is the read that says so instead of failing.
 */
@Composable
fun <T> rememberSource(key: SourceKey<T>): State<T> {
    require(key.level == ScopeLevel.App) {
        "widget data source '${key.id}' lives at ${key.level}: read it with rememberScopedSource"
    }
    val source = appSource(key) ?: error("no widget data source registered for '${key.id}'")
    return source.state.collectAsState()
}

/**
 * An app key's source: the registry's value, or else what the app level's factory
 * made for it.
 *
 * The registry is read through its local and not through the app level, because a
 * caller may stand the registry in for its own content. The palette's previews do,
 * so a widget drawn in the gallery shows stand-in data and not what the launcher is
 * doing.
 */
@Composable
internal fun <T> appSource(key: SourceKey<T>): WidgetDataSource<T>? {
    val registry = LocalWidgetDataRegistry.current
    val value = remember(key.id, registry) { registry.find(key) }
    return value ?: LocalScope.current?.find(key)
}

// Non-Composable accessors for the rule-engine / headless consumers: a
// synchronous current value and the raw flow, no recomposer required.
fun <T> WidgetDataRegistry.current(key: SourceKey<T>): T = get(key).state.value
fun <T> WidgetDataRegistry.flow(key: SourceKey<T>): StateFlow<T> = get(key).state

/** The value of [key] as this level sees it, or null where it is missing. */
fun <T> WidgetScope.current(key: SourceKey<T>): T? = find(key)?.state?.value
