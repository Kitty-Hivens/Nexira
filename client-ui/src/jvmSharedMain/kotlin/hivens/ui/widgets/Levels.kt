package hivens.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import hivens.widget.api.LocalScope
import hivens.widget.api.WidgetScope
import hivens.widget.api.rememberChildScope
import hivens.widget.model.ScopeLevel
import org.koin.compose.scope.UnboundKoinScope
import org.koin.core.annotation.KoinDelicateAPI
import org.koin.core.annotation.KoinExperimentalAPI

/**
 * Draws [content] inside [scope]: the widgets below read their sources from it,
 * and whatever is injected below resolves from its Koin scope first. With no scope
 * the content is drawn outside any level, which is where a screen rendered on its
 * own in a test stands.
 */
@OptIn(KoinExperimentalAPI::class, KoinDelicateAPI::class)
@Composable
fun LevelHost(scope: WidgetScope?, content: @Composable () -> Unit) {
    if (scope == null) {
        content()
        return
    }
    CompositionLocalProvider(LocalScope provides scope) {
        // Unbound because the level owns its Koin scope and closes it with itself,
        // where Koin's own scope composable would close it when this call leaves.
        UnboundKoinScope(scope.services, content)
    }
}

/**
 * A level of kind [level] inside the nearest one above, alive for as long as this
 * call stays composed, for an owner whose lifetime is its place in the composition.
 * With no level above, [content] is drawn outside any.
 */
@Composable
fun ChildLevel(level: ScopeLevel, content: @Composable () -> Unit) {
    val parent = LocalScope.current
    LevelHost(parent?.let { rememberChildScope(it, level) }, content)
}
