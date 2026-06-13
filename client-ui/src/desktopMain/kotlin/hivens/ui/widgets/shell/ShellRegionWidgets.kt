package hivens.ui.widgets.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.AppSidebar
import hivens.ui.AppState
import hivens.ui.RightPanel
import hivens.ui.Screen
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.editor.EditModeController
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.LocalSlotPath
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.koin.compose.koinInject

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

private const val RAIL_COLLAPSED_WIDTH = 20      // dp of the collapsed strip
private const val RAIL_SWIPE_THRESHOLD_PX = 48f  // horizontal travel that toggles

/**
 * Right region: the divider plus the news panel. removable=false. Collapsible at
 * runtime -- a chevron at the rail's top-right corner (beside the panel title)
 * or a horizontal swipe flips the persisted [ShellRegionProps.collapsed]; the
 * width animates and the center pane reclaims the space.
 */
@Widget(id = "appshell.region.right", displayName = "widget.appshell.region.right", removable = false, propsClass = ShellRegionProps::class)
@Composable
fun ShellRightRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellRegionProps>()
    val editing = LocalEditMode.current is EditModeState.On
    val path = LocalSlotPath.current
    val controller: EditModeController = koinInject()
    val s = LocalStrings.current
    val toggleCollapse: () -> Unit = {
        // Merge over the raw stored props so widthDp (and any other tuning)
        // survives the flip -- updateProps replaces the whole object.
        controller.updateProps(
            path,
            instance.instanceId,
            JsonObject(instance.props + ("collapsed" to JsonPrimitive(!props.collapsed))),
        )
    }

    // Edit mode keeps the static behavior: the collapsed region shows the
    // Tune-reachable strip (the prop panel is the editor's un-collapse path),
    // and runtime swipe/animation stay out of the way of arranging widgets.
    if (editing) {
        if (props.collapsed) { CollapsedRegionStrip(); return }
        val ctx = LocalShellContext.current
        Row(regionModifier(props)) {
            RegionDivider(props.showDivider)
            RightPanel(ctx.appState, ctx.onLogin, ctx.onLogout, ctx.sslBypass, Modifier.weight(1f).fillMaxHeight())
        }
        return
    }

    val expandedWidth = if (props.widthDp > 0) props.widthDp.dp else 265.dp
    val width by animateDpAsState(
        targetValue = if (props.collapsed) RAIL_COLLAPSED_WIDTH.dp else expandedWidth,
        label       = "rail-width",
    )
    val bg = if (props.glassAlpha > 0f) glassSurfaceAlpha(props.glassAlpha) else Color.Transparent

    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(bg)
            .pointerInput(props.collapsed) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd   = {
                        // Swipe right tucks the rail away; swipe left on the strip reopens it.
                        if (!props.collapsed && total > RAIL_SWIPE_THRESHOLD_PX) toggleCollapse()
                        else if (props.collapsed && total < -RAIL_SWIPE_THRESHOLD_PX) toggleCollapse()
                    },
                ) { change, dragAmount -> change.consume(); total += dragAmount }
            },
    ) {
        if (props.collapsed) {
            Box(
                modifier         = Modifier.fillMaxSize().background(glassSurfaceAlpha(0.4f)).clickable(onClick = toggleCollapse),
                contentAlignment = Alignment.TopCenter,
            ) {
                Icon(
                    imageVector        = Icons.Default.ChevronLeft,
                    contentDescription = s.railExpand,
                    tint               = CelestiaTheme.colors.textSecondary,
                    modifier           = Modifier.padding(top = 14.dp).size(18.dp),
                )
            }
        } else {
            val ctx = LocalShellContext.current
            Row(Modifier.fillMaxSize()) {
                RegionDivider(props.showDivider)
                RightPanel(ctx.appState, ctx.onLogin, ctx.onLogout, ctx.sslBypass, Modifier.weight(1f).fillMaxHeight())
            }
            // Collapse chevron at the rail's top-right, beside the panel title.
            IconButton(
                onClick  = toggleCollapse,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).size(26.dp),
            ) {
                Icon(
                    imageVector        = Icons.Default.ChevronRight,
                    contentDescription = s.railCollapse,
                    tint               = CelestiaTheme.colors.textSecondary,
                    modifier           = Modifier.size(18.dp),
                )
            }
        }
    }
}
