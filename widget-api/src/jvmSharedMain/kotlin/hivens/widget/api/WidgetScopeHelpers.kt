package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import hivens.widget.model.WidgetCommand

/**
 * What a widget finds for a key where it stands.
 *
 * Missing is an answer and not an error. A widget lands where its source does not
 * exist when a hand-edited layout puts it there, and it then has to draw a
 * placeholder rather than take the window down, which a throwing read would.
 *
 * Stable rather than immutable, because the value is whatever the key carries and
 * may hold snapshot state of its own. Both cases compare by content, so a child
 * handed an equal answer skips.
 */
@Stable
sealed interface ScopedSource<out T> {
    /** The key's current [value], read from the level the key declares. */
    data class Present<out T>(val value: T) : ScopedSource<T>

    /**
     * Nothing answers the key here. [levelFound] tells the two cases apart: false
     * when there is no level of the key's kind above the widget at all, which is
     * a widget placed where it does not belong, and true when the level is there
     * and has no source for the key.
     */
    data class Missing(val level: ScopeLevel, val levelFound: Boolean) : ScopedSource<Nothing>
}

/** The value, or null when the source is missing. */
fun <T> ScopedSource<T>.valueOrNull(): T? = (this as? ScopedSource.Present)?.value

/**
 * Values [ProvideScoped] put over levels for the subtree below it, innermost
 * first. Each is tied to the level it was put over, so a read climbing to another
 * level of the same kind does not see it.
 *
 * One object for as long as its level, its id and the chain below it stay, compared
 * by identity, with the value behind [state]. A new value is then a snapshot write
 * that recomposes the readers of this key, where a new overlay would change the
 * local and recompose every reader of every key below the provider.
 */
private class Overlay(val over: WidgetScope, val id: String, val state: State<Any?>, val below: Overlay?) {
    fun find(level: WidgetScope, id: String): Overlay? {
        var at: Overlay? = this
        while (at != null) {
            if (at.over === level && at.id == id) return at
            at = at.below
        }
        return null
    }
}

// Dynamic, so a value that changes recomposes the readers and not everything
// between them and the provider.
private val LocalOverlay: ProvidableCompositionLocal<Overlay?> = compositionLocalOf { null }

// The commands [ProvideScopedCommand] put over levels. Apart from the sources, so
// a source and a command that share an id cannot answer for each other.
private val LocalCommandOverlay: ProvidableCompositionLocal<Overlay?> = compositionLocalOf { null }

/**
 * Binds a widget to [key] wherever the key lives: climbs from the nearest level
 * to the one the key declares and reads it there, recomposing as the source emits
 * and as it is provided or withdrawn. A value a [ProvideScoped] above put over
 * that level wins over the level's own.
 *
 * Never throws for a key that is missing where the widget stands, see
 * [ScopedSource.Missing], nor for a factory that fails, which leaves the key
 * missing and is logged. The app level is always there, so an app key reads as
 * missing only when nothing registered it, the wiring bug [rememberSource] throws on.
 */
@Composable
@Suppress("UNCHECKED_CAST")
fun <T> rememberScopedSource(key: SourceKey<T>): ScopedSource<T> {
    if (key.level == ScopeLevel.App) {
        val source = appSource(key) ?: return ScopedSource.Missing(key.level, levelFound = true)
        val value by source.state.collectAsState()
        return ScopedSource.Present(value)
    }
    val level = LocalScope.current?.nearest(key.level) ?: return ScopedSource.Missing(key.level, levelFound = false)
    // The cast is the seam ProvideScoped's own signature closes: it put a T under this id.
    LocalOverlay.current?.find(level, key.id)?.let { return ScopedSource.Present(it.state.value as T) }
    val source = level.find(key) ?: return ScopedSource.Missing(key.level, levelFound = true)
    val value by source.state.collectAsState()
    return ScopedSource.Present(value)
}

/**
 * Puts [value] for [key] over the nearest level of the key's kind, for the widgets
 * in [content] and nowhere else. For a value a subtree builds for the widgets
 * below it, the way a page builds its own state.
 *
 * Nothing is written anywhere: the value travels down the composition with
 * [content], so a reader composed before this call or beside it cannot catch a
 * half-provided level, and the copy of a page still fading out keeps its own.
 * With no level of the key's kind above, [content] is drawn as it is and its
 * readers see the key as missing.
 */
@Composable
fun <T> ProvideScoped(key: SourceKey<T>, value: T, content: @Composable () -> Unit) {
    require(key.level != ScopeLevel.App) { "app-wide source '${key.id}' belongs in the WidgetDataRegistry" }
    CompositionLocalProvider(LocalOverlay provides rememberOverlay(LocalOverlay.current, key.level, key.id, value), content = content)
}

/** The overlay of [id] over the nearest level of kind [kind], or [below] where there is none. See [Overlay]. */
@Composable
private fun rememberOverlay(below: Overlay?, kind: ScopeLevel, id: String, value: Any?): Overlay? {
    val level = LocalScope.current?.nearest(kind)
    val state = rememberUpdatedState(value)
    return remember(level, id, below) { level?.let { Overlay(it, id, state, below) } ?: below }
}

/**
 * Puts [command] for [key] over the nearest level of the key's kind, for the
 * widgets in [content] and nowhere else, the way [ProvideScoped] does for a value.
 * For an owner whose command drives state it holds in the composition, a history
 * say. A new command recomposes the readers of this key alone.
 */
@Composable
fun <P> ProvideScopedCommand(key: CommandKey<P>, command: WidgetCommand<P>, content: @Composable () -> Unit) {
    require(key.level != ScopeLevel.App) { "app-wide command '${key.id}' belongs in the WidgetCommandRegistry" }
    CompositionLocalProvider(
        LocalCommandOverlay provides rememberOverlay(LocalCommandOverlay.current, key.level, key.id, command),
        content = content,
    )
}

/**
 * A command that runs the latest [block] it was handed and stays the same object
 * across recompositions, for an owner providing one with [ProvideScopedCommand] from
 * lambdas that are new on every pass.
 */
@Composable
fun <P> rememberLatestCommand(block: (P) -> Unit): WidgetCommand<P> {
    val latest = rememberUpdatedState(block)
    return remember { WidgetCommand { payload -> latest.value(payload) } }
}

/**
 * Binds a widget to the command [key] wherever the key lives, or null where it is
 * missing, the way [rememberScopedSource] binds a source. A command put over the
 * level by [ProvideScopedCommand] wins over the level's own.
 *
 * The dispatcher is the same object for as long as the answer stays, and it fires
 * whatever answers the key at the moment it is called.
 */
@Composable
fun <P> rememberScopedCommand(key: CommandKey<P>): ((P) -> Unit)? {
    val command = scopedCommand(key) ?: return null
    val current = rememberUpdatedState(command)
    return remember(key.id) { { payload: P -> current.value.run(payload) } }
}

/** Payload-less [rememberScopedCommand], named apart for the reason [rememberAction] is. */
@Composable
fun rememberScopedAction(key: CommandKey<Unit>): (() -> Unit)? {
    val command = scopedCommand(key) ?: return null
    val current = rememberUpdatedState(command)
    return remember(key.id) { { current.value.run(Unit) } }
}

@Composable
@Suppress("UNCHECKED_CAST")
private fun <P> scopedCommand(key: CommandKey<P>): WidgetCommand<P>? {
    if (key.level == ScopeLevel.App) {
        return LocalWidgetCommandRegistry.current.find(key) ?: LocalScope.current?.findCommand(key)
    }
    val level = LocalScope.current?.nearest(key.level) ?: return null
    // The cast is the seam ProvideScopedCommand's own signature closes.
    LocalCommandOverlay.current?.find(level, key.id)?.let { return it.state.value as WidgetCommand<P> }
    return level.findCommand(key)
}

/**
 * The ids in [needs] that nothing answers where the caller stands, in their order,
 * empty when the widget can draw. Each id is looked up in the registries of the
 * tree to learn its level, then answered the way a read would answer it: a value
 * put over the level by [ProvideScoped] or [ProvideScopedCommand], what the owner
 * provided, or what the registry or a factory supplies. An id nothing declares is
 * missing everywhere.
 *
 * Recomposes the caller when an owner provides or withdraws one of them.
 */
@Composable
fun missingNeeds(needs: List<String>): List<String> {
    if (needs.isEmpty()) return emptyList()
    val scope = LocalScope.current ?: return needs
    val sources = LocalOverlay.current
    val commands = LocalCommandOverlay.current
    return needs.filterNot { id ->
        val declaration = scope.declarationOf(id) ?: return@filterNot false
        val level = scope.nearest(declaration.level) ?: return@filterNot false
        val overlay = if (declaration.kind == KeyKind.Source) sources else commands
        overlay?.find(level, id) != null || scope.answers(declaration)
    }
}

/**
 * A level of kind [level] under [parent], made on first composition and disposed
 * when the caller leaves the composition or [parent] changes. For an owner whose
 * lifetime is its place in the composition, the way a window's shell is.
 */
@Composable
fun rememberChildScope(parent: WidgetScope, level: ScopeLevel): WidgetScope =
    remember(parent, level) { HeldLevel(parent.child(level)) }.level

/**
 * Closes [level] when the composition lets go of it. Abandoned counts too: a
 * composition that never applied still made the level, and only this hears about
 * it. A failure is logged and not thrown, because this runs while a frame applies.
 */
private class HeldLevel(val level: WidgetScope) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = level.closeLogged("a ${level.level} level")
    override fun onAbandoned() = level.closeLogged("a ${level.level} level")
}
