package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Draws [content] inside [level]: the level the widgets below read their keys from, with whatever else the host keeps
 * per level around it, the services injected below say. With no level, [content] is drawn outside any.
 */
typealias LevelHostDecorator = @Composable (level: WidgetScope?, content: @Composable () -> Unit) -> Unit

/**
 * How a widget that opens levels of its own draws inside one, a pages host one per page say. The kernel's default only
 * makes the level the nearest, and the host provides one that puts what it keeps per level around it as well.
 */
val LocalLevelHost: ProvidableCompositionLocal<LevelHostDecorator> = staticCompositionLocalOf {
    { level, content -> if (level == null) content() else CompositionLocalProvider(LocalScope provides level, content = content) }
}
