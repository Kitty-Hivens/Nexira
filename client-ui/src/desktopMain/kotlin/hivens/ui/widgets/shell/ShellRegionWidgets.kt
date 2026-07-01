package hivens.ui.widgets.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import hivens.core.data.SessionData
import hivens.ui.AppSidebar
import hivens.ui.AppState
import hivens.ui.RightPanel
import hivens.ui.Screen
import hivens.ui.chrome.HOST_IS_MAC
import hivens.ui.chrome.LocalChromeClose
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.chrome.LocalWindowState
import hivens.ui.chrome.WindowControls
import hivens.ui.chrome.WindowControlsMode
import hivens.ui.chrome.resolved
import hivens.ui.chrome.windowDragArea
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.editor.EditModeController
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.surface.Fill
import hivens.ui.surface.FrostRole
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.FrostSurface
import hivens.ui.surface.FrostTier
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.surface.toLayers
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import hivens.widget.api.LocalSlotPath
import hivens.widget.api.SlotRenderer
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
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
    @PropLabel("widget.appshell.region.showDivider") val showDivider: Boolean = false,
    @PropLabel("widget.appshell.region.collapsed") val collapsed: Boolean = false,
    @PropLabel("widget.appshell.region.swipeToCollapse") val swipeToCollapse: Boolean = true,
    // Surface depth for the right panel (the only region that renders a FrostSurface
    // today): Heavy = blur + scrim + tint + edge, so it reads as a distinct plane
    // and stays legible over any wallpaper. Left/center ignore it for now.
    @PropLabel("widget.appshell.region.frostTier") val frostTier: FrostTier = FrostTier.Heavy,
) {
    val glassAlpha: Float get() = glassAlphaPct / 100f
}

/**
 * Props for the RIGHT region only. It renders its own [NxSurface] (frost coat via
 * [frostTier]) instead of the flat glass backing the rails use, and it draws no
 * divider -- so [ShellRegionProps.glassAlphaPct] and `showDivider` are inert here
 * and simply do not exist on this class (the prop panel shows only what works).
 * Defaults are the panel's shipped look: a flat matte, swipe-to-collapse off.
 */
@Serializable
data class ShellRightRegionProps(
    @PropLabel("widget.appshell.region.widthDp") @PropRange(0.0, 600.0) val widthDp: Int = 0,
    @PropLabel("widget.appshell.region.collapsed") val collapsed: Boolean = false,
    @PropLabel("widget.appshell.region.swipeToCollapse") val swipeToCollapse: Boolean = false,
    @PropLabel("widget.appshell.region.frostTier") val frostTier: FrostTier = FrostTier.Flat,
)

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
    // Top-bar breadcrumb: the root-to-current path + back / forward / segment-jump,
    // sourced from the NavBackStack in AppRoot (navigation is not yet a widget surface).
    val trail: List<Screen>,
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val onBack: () -> Unit,
    val onForward: () -> Unit,
    val onPopTo: (Screen) -> Unit,
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
    val chrome = glassSurfaceAlpha(0.35f)
    val cornerDp = LocalStyle.current.cardCorner
    Box(Modifier.fillMaxSize().background(bg)) {
        LocalShellContext.current.centerBody()
        // Nestle the content's top-start corner into the chrome (Modrinth-style).
        // A chrome-colored wedge, not a clip -- clipping the (transparent over a
        // wallpaper) content would be invisible; the wedge reads either way.
        Box(
            Modifier.size(cornerDp).align(Alignment.TopStart).drawBehind {
                val r = size.minDimension
                val square = Path().apply { addRect(Rect(0f, 0f, r, r)) }
                val disc = Path().apply { addOval(Rect(0f, 0f, 2 * r, 2 * r)) }
                val wedge = Path().apply { op(square, disc, PathOperation.Difference) }
                drawPath(wedge, chrome)
            },
        )
    }
}

private const val RAIL_COLLAPSED_GRAB = 0 // collapsed reserves no width -- it is not part of the layout; reopen via Ctrl+N / edit-mode Tune
private val AUTO_COLLAPSE_BELOW = 980.dp   // window narrower than this auto-collapses the right rail

/**
 * Right region: the divider plus the news panel. removable=false. No handles or
 * strips: a horizontal swipe anywhere on the rail shuts it (the width tracks the
 * pointer and snaps on release; vertical scrolls and taps still reach the news).
 * Collapsed it keeps a slim transparent swipe-catch at the edge, so a swipe back
 * (or Ctrl+N) reopens it. Edit mode keeps the static prop-driven behaviour.
 */
@Widget(id = "appshell.region.right", displayName = "widget.appshell.region.right", removable = false, propsClass = ShellRightRegionProps::class)
@Composable
fun ShellRightRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellRightRegionProps>()
    // The panel is an NxSurface at Floating depth: a SurfaceContainerHigh body (a step
    // up the tonal ladder from the page) plus a luminance-derived bevel, so it reads
    // as a distinct plane over any wallpaper and with none. The editable frostTier
    // prop still drives the glass coat's richness.
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

    // RightPanel as a movableContentOf so the editing <-> non-editing branch swap below
    // (driven by the static LocalEditMode) MOVES the panel -- and its news SlotRenderer's
    // loaded state -- instead of disposing and reloading it on every Ctrl+E.
    val ctx = LocalShellContext.current
    val ctxState = rememberUpdatedState(ctx)
    val rightPanelMovable = remember {
        movableContentOf {
            val c = ctxState.value
            RightPanel(c.appState, c.onLogin, c.onLogout, c.sslBypass, Modifier.fillMaxSize())
        }
    }

    // Edit mode: static, no swipe/animation.
    if (editing) {
        if (props.collapsed) { CollapsedRegionStrip(); return }
        val sized = if (props.widthDp > 0) Modifier.width(props.widthDp.dp) else Modifier.width(265.dp)
        NxSurface(NxSurfaceLevel.Floating, sized.fillMaxHeight(), RectangleShape, tier = props.frostTier) {
            rightPanelMovable()
        }
        return
    }

    val density = LocalDensity.current
    // Auto-collapse on a narrow window so the rail stops reserving ~265dp the
    // content can't use -- the widgets reclaim the width and the pack banner
    // reaches the edge. A manual collapse still applies; swipe-to-open is
    // suppressed while auto-collapsed (no room to open into).
    val windowWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val autoCollapsed = windowWidthDp < AUTO_COLLAPSE_BELOW
    val effectiveCollapsed = props.collapsed || autoCollapsed
    val expandedWidth = if (props.widthDp > 0) props.widthDp.dp else 265.dp
    val expandedPx  = with(density) { expandedWidth.toPx() }
    // Collapsed keeps a slim transparent swipe-catch at the screen edge so the
    // rail can be dragged back open; the drag then ranges over the full width.
    val collapsedPx = with(density) { RAIL_COLLAPSED_GRAB.dp.toPx() }
    val widthAnim = remember { Animatable(if (effectiveCollapsed) collapsedPx else expandedPx) }
    val scope = rememberCoroutineScope()

    // Snap to the target whenever it changes from outside a drag (Ctrl+N / width
    // edit / window crossing the auto-collapse width). A drag never changes these
    // keys mid-flight, so it is not interrupted.
    LaunchedEffect(effectiveCollapsed, expandedPx, collapsedPx) {
        widthAnim.animateTo(if (effectiveCollapsed) collapsedPx else expandedPx)
    }

    // Horizontal swipe anywhere on the rail opens / closes it; vertical scrolls
    // and taps still reach the news (orthogonal gestures arbitrate by direction),
    // so it never fights the widgets. No handle, no strip, no fade.
    val swipe = if (props.swipeToCollapse && !autoCollapsed) {
        Modifier.pointerInput(collapsedPx, expandedPx) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, delta ->
                    change.consume()
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

    Box(
        modifier = Modifier
            .width(with(density) { widthAnim.value.toDp() })
            .fillMaxHeight()
            .clipToBounds()
            .then(swipe),
    ) {
        // Hide the content while basically collapsed so the slim catch shows no
        // clipped sliver; it wipes in (at full width, requiredWidth -- no reflow)
        // as the rail widens.
        if (widthAnim.value > collapsedPx + 1f) {
            NxSurface(NxSurfaceLevel.Floating, Modifier.fillMaxSize(), RectangleShape, tier = props.frostTier) {
                Row(modifier = Modifier.requiredWidth(expandedWidth).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) { rightPanelMovable() }
                }
            }
        }
    }
}

// ── Top region: the custom title bar ──────────────────────────────────────────

/** Bar silhouette. Hug = flush to the edge; Float = inset, rounded, detached;
 *  Rect = flush, square. (Vocabulary mirrors the Hyprland/Quickshell bar.) */
@Serializable
enum class CornerStyle { Hug, Float, Rect }

/** How the bar's clusters are separated. Pills = each cluster its own frosted
 *  surface; LineSeparated = one surface with hairline dividers. */
@Serializable
enum class GroupStyle { Pills, LineSeparated }

@Serializable
data class ShellTopRegionProps(
    @PropLabel("widget.appshell.topbar.heightDp") @PropRange(36.0, 72.0) val heightDp: Int = 44,
    @PropLabel("widget.appshell.topbar.cornerStyle") val cornerStyle: CornerStyle = CornerStyle.Rect,
    @PropLabel("widget.appshell.topbar.groupStyle") val groupStyle: GroupStyle = GroupStyle.LineSeparated,
    @PropLabel("widget.appshell.topbar.frostTier") val frostTier: FrostTier = FrostTier.Flat,
    @PropLabel("widget.appshell.topbar.controls") val controls: WindowControlsMode = WindowControlsMode.Auto,
)

private const val TOPBAR_SURFACE = "appshell.topbar"

/**
 * Top region: the custom title bar. Replaces the OS chrome -- hosts the
 * breadcrumb / status widgets (the appshell.topbar sub-surface), a draggable
 * center lane, and the caption buttons. Caption buttons are chrome (not a
 * widget): placed left on macOS, right elsewhere, and shown per
 * [WindowControlsMode] (hidden by default on tiling WMs). removable=false --
 * losing the bar would strand window controls on a floating DE.
 */
@Widget(id = "appshell.region.top", displayName = "widget.appshell.region.top", removable = false, propsClass = ShellTopRegionProps::class)
@Composable
fun ShellTopRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellTopRegionProps>()
    val windowState = LocalWindowState.current
    val composeWindow = LocalComposeWindow.current
    val onClose = LocalChromeClose.current
    // With OS decorations (useCustomChrome off) the window already has caption
    // buttons + drag + resize, so the bar's chrome stands down; the breadcrumb
    // still renders (it is content, not window chrome).
    val useCustomChrome = LocalUseCustomChrome.current
    val showControls = useCustomChrome && props.controls.resolved() && windowState != null
    // In edit mode the center is a widget drop-lane, not a window-drag zone --
    // otherwise dragging there moves the window instead of rearranging widgets.
    val editing = LocalEditMode.current is EditModeState.On

    val maximizeToggle: () -> Unit = {
        windowState?.let {
            it.placement = if (it.placement == WindowPlacement.Maximized) WindowPlacement.Floating
            else WindowPlacement.Maximized
        }
    }
    val corner = LocalStyle.current.cardCorner
    // Hug = flush to the window's top edge, only the bottom corners curve into the
    // body so it reads as part of the chrome; Float = a detached, inset, all-round
    // pill; Rect = a plain flush rectangle.
    val shape = when (props.cornerStyle) {
        CornerStyle.Hug   -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
        CornerStyle.Float -> RoundedCornerShape(corner)
        CornerStyle.Rect  -> RectangleShape
    }
    val outerPad = if (props.cornerStyle == CornerStyle.Float) 6.dp else 0.dp
    // The top bar reads as "especially dark" by default: a themed dark fill via
    // the background role (a future theme engine re-tints it). Picking a frost
    // tier opts into the glass look instead.
    val layers = if (props.frostTier == FrostTier.Flat) {
        // Same tone as the left rail (AppSidebar uses glassSurfaceAlpha(0.35) =
        // surface @ 0.35), so the top bar and rail read as ONE combined chrome
        // panel rather than two separate strips. Fill(Surface, 0.35) resolves to
        // the identical color (incl. the glass-intensity knob).
        listOf(Fill(role = FrostRole.Surface, alpha = 0.35f))
    } else {
        props.frostTier.toLayers()
    }

    @Composable
    fun Caption() {
        if (showControls) WindowControls(windowState, onClose)
    }

    @Composable
    fun RowScope.DragLane() {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .then(if (editing || !useCustomChrome) Modifier else Modifier.windowDragArea(composeWindow, maximizeToggle)),
            contentAlignment = Alignment.Center,
        ) {
            SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("center"), spacing = 4.dp)
        }
    }

    val barModifier = Modifier.fillMaxWidth().height(props.heightDp.dp).padding(outerPad)

    when (props.groupStyle) {
        GroupStyle.LineSeparated -> FrostSurface(layers = layers, modifier = barModifier, shape = shape) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (HOST_IS_MAC) Caption()
                AppGlyph()
                BarDivider()
                SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("left"), spacing = 4.dp)
                DragLane()
                SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("right"), spacing = 4.dp)
                if (!HOST_IS_MAC) Caption()
            }
        }

        GroupStyle.Pills -> Row(
            barModifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (HOST_IS_MAC && showControls) {
                FrostSurface(layers, Modifier.fillMaxHeight(), shape) { Caption() }
            }
            AppGlyph()
            FrostSurface(layers, Modifier.fillMaxHeight(), shape) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("left"), spacing = 4.dp)
                }
            }
            DragLane()
            FrostSurface(layers, Modifier.fillMaxHeight(), shape) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("right"), spacing = 4.dp)
                }
            }
            if (!HOST_IS_MAC && showControls) {
                FrostSurface(layers, Modifier.fillMaxHeight(), shape) { Caption() }
            }
        }
    }
}

/** App-identity mark anchoring the bar's left edge: a crescent moon in the accent
 *  colour, the Celestia/Nexira lunar motif. A fixed chrome glyph (not a slot widget)
 *  so it shows for everyone without a graph migration. */
@Composable
private fun AppGlyph() {
    Symbol(
        icon = NxIcon.DarkMode,
        contentDescription = null,
        modifier = Modifier.padding(start = 10.dp, end = 6.dp),
        tint = NxTheme.colors.primary,
        size = 20.dp,
    )
}

/** Hairline separating the app glyph (identity) from the navigation + breadcrumb
 *  cluster, so the two read as distinct zones rather than one crowded row. */
@Composable
private fun BarDivider() {
    VerticalDivider(
        modifier = Modifier.height(18.dp).padding(horizontal = 4.dp),
        color = NxTheme.colors.outline,
    )
}

/**
 * Body region: the nested Row of the three original shell regions (left rail,
 * center, right panel). It exists so the root surface can stack the top bar over
 * the body in a Column; the Row itself lives in the appshell.body sub-surface
 * (the orientation comes from that slot, mirroring the appshell.leftrail nesting).
 * removable=false -- it carries the entire app body.
 */
@Widget(id = "appshell.region.body", displayName = "widget.appshell.region.body", removable = false)
@Composable
fun ShellBodyRegion(instance: WidgetInstance) {
    SlotRenderer(SurfaceId("appshell.body"), SlotId("content"), Modifier.fillMaxSize())
}
