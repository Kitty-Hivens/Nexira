package hivens.ui.widgets.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.AppSidebar
import hivens.ui.AppState
import hivens.ui.RightPanel
import hivens.ui.Screen
import hivens.ui.customization.glassSurfaceAlpha
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

/**
 * Everything the three shell regions need, provided once by [hivens.ui.AppLayout]
 * so the region widgets (which render through SlotRenderer and take no params)
 * can build the rails + center. `compositionLocalOf` so only the region readers
 * recompose when the shell state changes, not the whole subtree.
 *
 * [centerBody] is the screen router; it is supplied here rather than stored in
 * the layout graph because the navigation tree is not (yet) a widget surface.
 */
class ShellContext(
    val currentScreen: Screen,
    val isAuthenticated: Boolean,
    val onScreenChange: (Screen) -> Unit,
    val onLogout: () -> Unit,
    val appState: AppState,
    val onLogin: (SessionData) -> Unit,
    val sslBypass: Boolean,
    val centerBody: @Composable () -> Unit,
)

val LocalShellContext = compositionLocalOf<ShellContext> {
    error("LocalShellContext not provided -- AppLayout must wrap the shell surface")
}

/**
 * Left region: the navigation rail plus the divider that separates it from the
 * center. removable=false -- losing the rail would navigation-lock the launcher.
 * The frame (width, glass) is still owned by AppSidebar here; Phase 3 lifts it
 * into region props.
 */
@Widget(id = "appshell.region.left", displayName = "Left rail", removable = false)
@Composable
fun ShellLeftRegion(instance: WidgetInstance) {
    val ctx = LocalShellContext.current
    Row(Modifier.fillMaxHeight()) {
        AppSidebar(
            currentScreen   = ctx.currentScreen,
            isAuthenticated = ctx.isAuthenticated,
            onScreenChange  = ctx.onScreenChange,
            onLogout        = ctx.onLogout,
        )
        VerticalDivider(Modifier.fillMaxHeight(), color = glassSurfaceAlpha(0.6f))
    }
}

/**
 * Center region: the screen router. Carries weight=1 in the default layout so it
 * flexes between the two fixed-width rails. removable=false -- without it there
 * is no content area.
 */
@Widget(id = "appshell.region.center", displayName = "Main content", removable = false)
@Composable
fun ShellCenterRegion(instance: WidgetInstance) {
    Box(Modifier.fillMaxSize()) {
        LocalShellContext.current.centerBody()
    }
}

/**
 * Right region: the divider plus the auth + news panel. removable=false -- the
 * auth panel is the only sign-in entry point.
 */
@Widget(id = "appshell.region.right", displayName = "Right panel", removable = false)
@Composable
fun ShellRightRegion(instance: WidgetInstance) {
    val ctx = LocalShellContext.current
    Row(Modifier.fillMaxHeight()) {
        VerticalDivider(Modifier.fillMaxHeight(), color = glassSurfaceAlpha(0.6f))
        RightPanel(
            appState  = ctx.appState,
            onLogin   = ctx.onLogin,
            onLogout  = ctx.onLogout,
            sslBypass = ctx.sslBypass,
            modifier  = Modifier.width(264.dp).fillMaxHeight(),
        )
    }
}
