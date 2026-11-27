package hivens.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import hivens.ui.widgets.NxMapControls
import hivens.ui.widgets.NxViewportScrollbar
import hivens.ui.widgets.PlayedWidgetEntrance
import hivens.ui.widgets.WidgetSurface
import hivens.ui.widgets.modules.WidgetModules
import hivens.ui.widgets.state.WidgetStateStore
import hivens.widget.api.LocalMapControls
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalViewportScrollbar
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.LocalWidgetEntrance
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.LocalWidgetSurfaceRenderer
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WidgetSurfaceRenderer
import org.koin.compose.koinInject
import hivens.widget.api.LocalLevelHost
import hivens.widget.api.LevelHostDecorator
import hivens.ui.widgets.LevelHost

/**
 * What every window of the launcher hands the widgets it draws, whatever it shows: the widgets that exist, the
 * registries of the keys, the app level, the state a widget keeps, and how a widget's plane, its arrival, a scrollbar
 * and a map's controls are drawn. The arrangement is the document's, which the composition drawn provides. Read from the launcher's graph, so a window drawn inside it
 * needs nothing else to draw a widget. One place, so a window that draws widgets cannot leave one of them out.
 */
@Composable
fun KernelLocals(content: @Composable () -> Unit) {
    val widgetModules: WidgetModules = koinInject()
    val services: WidgetServiceRegistry = koinInject()
    val data: WidgetDataRegistry = koinInject()
    val commands: WidgetCommandRegistry = koinInject()
    val app: WidgetScope = koinInject()
    val state: WidgetStateStore = koinInject()
    // The registry as it is now. A module switched on or off, or the folder read again,
    // is a new value here and a whole-tree recomposition, which is what a change to the
    // set of widgets that exist is.
    val modules by widgetModules.state.collectAsState()
    // Remembered so its identity stays stable: it is provided through a static local, and
    // a fresh identity would invalidate the whole subtree rather than the widgets that
    // have a plane.
    val surfaceRenderer: WidgetSurfaceRenderer = remember { { spec, body -> WidgetSurface(spec, body) } }
    CompositionLocalProvider(
        LocalWidgetRegistry        provides modules.registry,
        LocalWidgetServiceRegistry provides services,
        LocalWidgetDataRegistry    provides data,
        LocalScope                 provides app,
        LocalWidgetCommandRegistry provides commands,
        LocalWidgetStateHost       provides state,
        LocalWidgetSurfaceRenderer provides surfaceRenderer,
        LocalWidgetEntrance        provides PlayedWidgetEntrance,
        LocalViewportScrollbar     provides NxViewportScrollbar,
        LocalMapControls           provides NxMapControls,
        // A level a widget opens is drawn with its Koin scope around it, as every level the host opens is.
        LocalLevelHost             provides LauncherLevelHost,
        content = content,
    )
}

/** The host's [LocalLevelHost]: a level with what the launcher keeps per level, its Koin scope, around it. */
private val LauncherLevelHost: LevelHostDecorator = { level, content -> LevelHost(level, content) }
