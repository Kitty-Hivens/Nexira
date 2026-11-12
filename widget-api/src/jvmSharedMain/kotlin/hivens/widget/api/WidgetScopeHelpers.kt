package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

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
 */
private data class Overlay(val over: WidgetScope, val id: String, val value: Any?, val below: Overlay?) {
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
    LocalOverlay.current?.find(level, key.id)?.let { return ScopedSource.Present(it.value as T) }
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
    val below = LocalOverlay.current
    val level = LocalScope.current?.nearest(key.level)
    val overlay = if (level == null) below else Overlay(level, key.id, value, below)
    CompositionLocalProvider(LocalOverlay provides overlay, content = content)
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
