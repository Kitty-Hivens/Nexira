package hivens.widget.model

/**
 * The write counterpart of [WidgetDataSource]: a one-shot action a widget
 * triggers declaratively instead of injecting a concrete service and calling it.
 * A widget calls `rememberCommand(key)(payload)` (or `rememberAction(key)()` for
 * a payload-less one) and neither knows nor cares which service the action drives.
 *
 * Compose-free so the same commands feed a non-Compose consumer -- the future
 * rule-engine fires a command headlessly, the symmetric counterpart to reading a
 * source's [kotlinx.coroutines.flow.StateFlow] value.
 *
 * Fire-and-forget and Unit-returning by contract: a command drives a side effect,
 * it does not return a result -- read a [WidgetDataSource] for that. Suspend work
 * is wrapped onto a scope at registration, so [run] itself is synchronous and
 * implementations must tolerate invocation off the main thread.
 */
fun interface WidgetCommand<P> {
    fun run(payload: P)
}

/**
 * Stable, typed handle to a command. The [id] is the wire identity -- the
 * rule-engine, layout editor, and any JSON wiring reference a command by this
 * string -- while the phantom [P] gives call-site type-safety on `register` /
 * `dispatch` / `rememberCommand`.
 *
 * Invariant (unlike the producer-side [SourceKey], which is `out T`): the phantom
 * and the command parameter both describe the one payload flowing *in* at
 * dispatch, so invariance keeps the registry's dispatch cast as defensible as the
 * read side's get cast -- one id maps to exactly one [P]. String-keyed for the
 * same reason as [SourceKey]: two commands can share a Kotlin type, and a
 * declarative rule references a command by a stable name, not a class.
 *
 * [level] is where the command lives, the way [SourceKey.level] says it for a
 * source. A command that drives one window's history has an answer per window and
 * cannot be one value for the whole app. [ScopeLevel.App], the default, is the
 * app-wide registry every key declared before levels existed fires through.
 */
class CommandKey<P>(val id: String, val level: ScopeLevel = ScopeLevel.App) {
    override fun equals(other: Any?): Boolean = other is CommandKey<*> && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "CommandKey($id, $level)"
}
