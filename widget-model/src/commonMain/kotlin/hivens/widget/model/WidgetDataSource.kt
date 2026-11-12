package hivens.widget.model

import kotlinx.coroutines.flow.StateFlow

/**
 * A reactive, app-provided data source a widget binds to declaratively instead
 * of injecting a concrete service. The QML/shell counterpart of "bind to a
 * service property": a widget reads `rememberSource(key)` and neither knows nor
 * cares which service backs it.
 *
 * [StateFlow] (not a Compose `State`) keeps this Compose-free so the same
 * sources feed a non-Compose consumer -- the future rule-engine reads
 * [StateFlow.value] synchronously, while Compose widgets convert via
 * `collectAsState()`. Mirrors the existing `WidgetService` exposure shape.
 *
 * Read-only by design: a source is data the widget observes. Driving a service
 * (a command / write) is a separate concern, not this contract.
 *
 * A source a level made from a factory is disposed with that level. One that holds
 * something to release implements [AutoCloseable], and the level closes it.
 */
interface WidgetDataSource<out T> {
    val state: StateFlow<T>
}

/**
 * Stable, typed handle to a source. The [id] is the wire identity -- the
 * rule-engine, layout editor, and any JSON wiring reference a source by this
 * string -- while the phantom [T] gives call-site type-safety on
 * `rememberSource` / registration.
 *
 * String-keyed (not type-keyed like [WidgetService] lookups) on purpose: two
 * sources can share a Kotlin type (two `StateFlow<List<Server>>`), and a
 * declarative rule references a source by a stable name, not a class.
 *
 * [level] is where the source lives. A widget reading the key is served by the
 * nearest level of that kind above it, so one key cannot be answered from two
 * places. [ScopeLevel.App], the default, is the app-wide registry every key
 * declared before levels existed reads from.
 *
 * Equality stays on the id alone, because the id is the wire identity: two keys
 * with one id and different levels are a wiring bug, not two sources.
 */
class SourceKey<out T>(val id: String, val level: ScopeLevel = ScopeLevel.App) {
    override fun equals(other: Any?): Boolean = other is SourceKey<*> && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "SourceKey($id, $level)"
}
