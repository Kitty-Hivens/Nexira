package hivens.ui.widgets.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import hivens.widget.api.LocalSlotPath
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.launch
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
    @PropLabel("widget.appshell.region.swipeToCollapse") val swipeToCollapse: Boolean = true,
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
// edit chrome (and its Tune affordance -- the only un-collapse path in edit
// mode) stays hoverable. A fully-returned region leaves nothing to hover.
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

private const val RAIL_COLLAPSED_WIDTH = 12 // dp of the collapsed edge strip

/**
 * Right region: the divider plus the news panel. removable=false. Collapsed
 * with no buttons -- a horizontal swipe drags the rail open/shut (the width
 * tracks the pointer and snaps on release), and Ctrl+N toggles it from anywhere.
 * Edit mode keeps the static prop-driven behaviour so arranging widgets is calm.
 */
@Widget(id = "appshell.region.right", displayName = "widget.appshell.region.right", removable = false, propsClass = ShellRegionProps::class)
@Composable
fun ShellRightRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellRegionProps>()
    val editing = LocalEditMode.current is EditModeState.On
    val path = LocalSlotPath.current
    val controller: EditModeController = koinInject()
    val toggleCollapse: () -> Unit = {
        // Merge over the raw stored props so widthDp (and other tuning) survives
        // the flip -- updateProps replaces the whole object.
        controller.updateProps(
            path,
            instance.instanceId,
            JsonObject(instance.props + ("collapsed" to JsonPrimitive(!props.collapsed))),
        )
    }
    // Ctrl+N (window-level, see AppShell) toggles the rail. rememberUpdatedState
    // keeps the flip reading the latest collapsed value across recompositions.
    val currentToggle by rememberUpdatedState(toggleCollapse)
    LaunchedEffect(Unit) {
        var seen = controller.rightRailToggleSignal.value
        snapshotFlow { controller.rightRailToggleSignal.value }.collect { tick ->
            if (tick != seen) { seen = tick; currentToggle() }
        }
    }

    // Edit mode: static, no swipe/animation.
    if (editing) {
        if (props.collapsed) { CollapsedRegionStrip(); return }
        val ctx = LocalShellContext.current
        Row(regionModifier(props)) {
            RegionDivider(props.showDivider)
            RightPanel(ctx.appState, ctx.onLogin, ctx.onLogout, ctx.sslBypass, Modifier.weight(1f).fillMaxHeight())
        }
        return
    }

    val density = LocalDensity.current
    val expandedWidth = if (props.widthDp > 0) props.widthDp.dp else 265.dp
    val collapsedPx = with(density) { RAIL_COLLAPSED_WIDTH.dp.toPx() }
    val expandedPx  = with(density) { expandedWidth.toPx() }
    val widthAnim = remember { Animatable(if (props.collapsed) collapsedPx else expandedPx) }
    val scope = rememberCoroutineScope()

    // Snap to the target whenever it changes from outside a drag (prop edit /
    // Ctrl+N / width change). A drag never changes these keys mid-flight, so it
    // is not interrupted.
    LaunchedEffect(props.collapsed, expandedPx, collapsedPx) {
        widthAnim.animateTo(if (props.collapsed) collapsedPx else expandedPx)
    }

    val swipe = if (props.swipeToCollapse) {
        Modifier.pointerInput(collapsedPx, expandedPx) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, delta ->
                    change.consume()
                    // Rail sits on the right: dragging left (negative delta) widens it.
                    scope.launch { widthAnim.snapTo((widthAnim.value - delta).coerceIn(collapsedPx, expandedPx)) }
                },
                onDragEnd = {
                    val collapse = widthAnim.value < (collapsedPx + expandedPx) / 2f
                    scope.launch { widthAnim.animateTo(if (collapse) collapsedPx else expandedPx) }
                    if (collapse != props.collapsed) toggleCollapse()
                },
            )
        }
    } else {
        Modifier
    }

    val bg = if (props.glassAlpha > 0f) glassSurfaceAlpha(props.glassAlpha) else Color.Transparent
    val ctx = LocalShellContext.current

    Box(
        modifier = Modifier
            .width(with(density) { widthAnim.value.toDp() })
            .fillMaxHeight()
            .background(bg)
            .then(swipe),
    ) {
        Row(Modifier.fillMaxSize()) {
            RegionDivider(props.showDivider)
            // Below ~the collapsed strip the panel has no room; drop it so a thin
            // edge does not try to lay out the whole news rail.
            if (widthAnim.value > collapsedPx + 1f) {
                RightPanel(ctx.appState, ctx.onLogin, ctx.onLogout, ctx.sslBypass, Modifier.weight(1f).fillMaxHeight())
            } else {
                Spacer(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
