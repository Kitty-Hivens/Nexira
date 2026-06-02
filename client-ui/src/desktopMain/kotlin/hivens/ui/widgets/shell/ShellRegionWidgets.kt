package hivens.ui.widgets.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.AppSidebar
import hivens.ui.AppState
import hivens.ui.RightPanel
import hivens.ui.Screen
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

/**
 * Editable frame of one shell region. [widthDp] 0 means flex (the region's
 * per-instance weight drives its share of the Row); > 0 pins a fixed width.
 * [glassAlpha] 0 means no backing. The defaults reproduce the pre-widget shell.
 */
@Serializable
data class ShellRegionProps(
    @PropLabel("widget.appshell.region.widthDp") @PropRange(0.0, 600.0) val widthDp: Int = 0,
    @PropLabel("widget.appshell.region.glassAlphaPct") @PropRange(0.0, 100.0) val glassAlphaPct: Int = 0,
    @PropLabel("widget.appshell.region.showDivider") val showDivider: Boolean = true,
    @PropLabel("widget.appshell.region.collapsed") val collapsed: Boolean = false,
) {
    val glassAlpha: Float get() = glassAlphaPct / 100f
}

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

// Width + optional glass backing shared by the two rails. Flex (widthDp 0)
// leaves sizing to the SlotRenderer weight wrapper; fixed pins a width.
@Composable
private fun regionModifier(props: ShellRegionProps): Modifier {
    val sized = if (props.widthDp > 0) Modifier.width(props.widthDp.dp) else Modifier
    val bg = if (props.glassAlpha > 0f) glassSurfaceAlpha(props.glassAlpha) else Color.Transparent
    return sized.fillMaxHeight().background(bg)
}

@Composable
private fun RowScope.RegionDivider(show: Boolean) {
    if (show) VerticalDivider(Modifier.fillMaxHeight(), color = glassSurfaceAlpha(0.6f))
}

// Shown for a collapsed region while editing: thin but visible, so the region's
// edit chrome (and its Tune affordance -- the only un-collapse path) stays
// hoverable. A fully-returned region leaves nothing to hover, stranding the user.
@Composable
private fun CollapsedRegionStrip() {
    Box(Modifier.width(22.dp).fillMaxHeight().background(glassSurfaceAlpha(0.4f)))
}

/**
 * Left region: the navigation rail plus the divider that separates it from the
 * center. removable=false -- losing the rail would navigation-lock the launcher.
 */
@Widget(id = "appshell.region.left", displayName = "widget.appshell.region.left", removable = false, propsClass = ShellRegionProps::class)
@Composable
fun ShellLeftRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellRegionProps>()
    if (props.collapsed) {
        // Render nothing in production; keep a thin visible strip in edit mode so
        // the prop panel (the only un-collapse path) stays reachable via Tune.
        if (LocalEditMode.current is EditModeState.On) CollapsedRegionStrip()
        return
    }
    val ctx = LocalShellContext.current
    Row(regionModifier(props)) {
        AppSidebar(
            currentScreen   = ctx.currentScreen,
            isAuthenticated = ctx.isAuthenticated,
            onScreenChange  = ctx.onScreenChange,
            onLogout        = ctx.onLogout,
            modifier        = Modifier.weight(1f).fillMaxHeight(),
        )
        RegionDivider(props.showDivider)
    }
}

/**
 * Center region: the screen router. Carries weight=1 in the default layout so it
 * flexes between the two rails. removable=false and never collapsible -- without
 * it there is no content area.
 */
@Widget(id = "appshell.region.center", displayName = "widget.appshell.region.center", removable = false, propsClass = ShellRegionProps::class)
@Composable
fun ShellCenterRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellRegionProps>()
    val bg = if (props.glassAlpha > 0f) glassSurfaceAlpha(props.glassAlpha) else Color.Transparent
    Box(Modifier.fillMaxSize().background(bg)) {
        LocalShellContext.current.centerBody()
    }
}

/**
 * Right region: the divider plus the auth + news panel. removable=false -- the
 * auth panel is the only sign-in entry point.
 */
@Widget(id = "appshell.region.right", displayName = "widget.appshell.region.right", removable = false, propsClass = ShellRegionProps::class)
@Composable
fun ShellRightRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellRegionProps>()
    if (props.collapsed) {
        if (LocalEditMode.current is EditModeState.On) CollapsedRegionStrip()
        return
    }
    val ctx = LocalShellContext.current
    Row(regionModifier(props)) {
        RegionDivider(props.showDivider)
        RightPanel(
            appState  = ctx.appState,
            onLogin   = ctx.onLogin,
            onLogout  = ctx.onLogout,
            sslBypass = ctx.sslBypass,
            modifier  = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
