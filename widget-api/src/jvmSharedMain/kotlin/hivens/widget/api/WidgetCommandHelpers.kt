package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Wraps a plain side-effecting block as a synchronous [WidgetCommand]. */
fun <P> command(block: (P) -> Unit): WidgetCommand<P> = WidgetCommand { block(it) }

/**
 * Wraps a suspend block fire-and-forget on [scope], so a suspend service call
 * (e.g. an update check or a skin upload) can back a synchronous [WidgetCommand].
 * The scope is captured here at registration -- the registry stays scope-free and
 * trivially constructible (mirrors how EditModeController takes the app scope).
 */
fun <P> suspendCommand(scope: CoroutineScope, block: suspend (P) -> Unit): WidgetCommand<P> =
    WidgetCommand { payload -> scope.launch { block(payload) } }

/**
 * Binds a widget to a command by [key] and returns a dispatcher: the widget says
 * what it wants to happen, not which service does it. Memoized on the id. Errors
 * on dispatch if nothing answers the key: an app command is always there, so a
 * miss is a wiring bug, the same contract as [rememberSource].
 *
 * Answered the way [rememberSource] answers an app source: the registry's command,
 * or else the one the app level makes from the registry's factory for the key.
 *
 * App keys only, for the reason [rememberSource] takes only those: a command of
 * another level can be missing where a widget stands, and [rememberScopedCommand]
 * is the read that says so instead of failing.
 */
@Composable
fun <P> rememberCommand(key: CommandKey<P>): (P) -> Unit {
    requireApp(key)
    val registry = LocalWidgetCommandRegistry.current
    val scope = LocalScope.current
    return remember(key.id, registry, scope) { { payload: P -> appCommand(key, registry, scope).run(payload) } }
}

/**
 * Payload-less variant of [rememberCommand]. Separate name, not an overload: a
 * `CommandKey<Unit>` overload of rememberCommand would differ from the generic
 * one only by return type (`() -> Unit` vs `(Unit) -> Unit`), which is not a
 * legal overload.
 */
@Composable
fun rememberAction(key: CommandKey<Unit>): () -> Unit {
    requireApp(key)
    val registry = LocalWidgetCommandRegistry.current
    val scope = LocalScope.current
    return remember(key.id, registry, scope) { { appCommand(key, registry, scope).run(Unit) } }
}

/** What answers the app command [key] at the moment it is fired. Throws when nothing does. */
private fun <P> appCommand(key: CommandKey<P>, registry: WidgetCommandRegistry, scope: WidgetScope?): WidgetCommand<P> =
    registry.find(key) ?: scope?.findCommand(key) ?: error("no widget command registered for '${key.id}'")

private fun requireApp(key: CommandKey<*>) {
    require(key.level == ScopeLevel.App) {
        "widget command '${key.id}' lives at ${key.level}: read it with rememberScopedCommand"
    }
}
