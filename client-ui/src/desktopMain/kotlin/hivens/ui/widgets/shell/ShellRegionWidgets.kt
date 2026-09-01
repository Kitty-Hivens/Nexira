package hivens.ui.widgets.shell

import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.AppSidebar
import hivens.ui.AppState
import hivens.ui.RightPanel
import hivens.ui.Screen
import hivens.ui.chrome.HOST_IS_MAC
import hivens.ui.chrome.LocalChromeClose
import hivens.ui.chrome.LocalComposeWindow
import hivens.ui.chrome.LocalUseCustomChrome
import hivens.ui.chrome.LocalWindowMaximizer
import hivens.ui.chrome.LocalWindowState
import hivens.ui.chrome.WindowControls
import hivens.ui.chrome.WindowControlsMode
import hivens.ui.chrome.resolved
import hivens.ui.chrome.windowDragArea
import hivens.ui.editor.EditModeController
import hivens.ui.editor.EditModeState
import hivens.ui.editor.LocalEditMode
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
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
 * The chrome's opacity when nothing overrides it.
 *
 * The rail, the top bar and the centre's corner wedge all draw at this number, and
 * the wedge only does its job -- carrying the content's corner into the chrome --
 * while it is exactly the colour of the plane it joins. They were three separate
 * literals that happened to agree, which is how they came apart: a plane whose
 * opacity was refused on light left a visible patch at the seam.
 */
private const val CHROME_OPACITY_PCT = 35

/**
 * Props for the CENTRE region: the page under every screen.
 *
 * It had five, of which it read one. The other four -- a width the weight decides, a
 * divider the rails draw, a collapse it does not have and the swipe that would drive
 * it -- were a props class shared with regions that do use them, so the panel offered
 * the centre four knobs that moved nothing.
 *
 * The one it did read was a raw alpha handed to the tinting helper, which snaps to a
 * tonal rung on light: two different numbers came out as one pixel there, and no
 * amount of the slider made the page translucent. It is an [NxSurface] like its
 * siblings now, so the same pair of values means the same thing in all four regions.
 */
@Serializable
data class ShellCenterRegionProps(
    @PropLabel("widget.appshell.region.opacityPct") @PropRange(-1.0, 100.0) val opacityPct: Int = 0,
    @PropLabel("widget.appshell.region.blurDp") @PropRange(0.0, 40.0) val blurDp: Int = 0,
)

/**
 * A region's plane, as the two numbers it actually has.
 *
 * They were one name before -- a preset -- and the name moved both at once, in
 * opposite directions: one step was blur 18 at 55% fill, the next blur 28 at 45%, so
 * asking for more blur quietly asked for less fill. Two of the four values the preset
 * carried never reached a pixel at all.
 *
 * -1 means "the theme's own floor", which is what an unnamed opacity has always
 * drawn: 92% on dark, solid on light.
 */
internal fun Int.regionOpacity(): Float? = takeIf { it >= 0 }?.let { it / 100f }

/**
 * Props for the RIGHT region. It draws no divider, so no divider knob exists here --
 * the prop panel shows only what works. Defaults are the panel's shipped look: the
 * theme's own floor, no blur, swipe-to-collapse off.
 */
@Serializable
data class ShellRightRegionProps(
    @PropLabel("widget.appshell.region.widthDp") @PropRange(0.0, 600.0) val widthDp: Int = 0,
    @PropLabel("widget.appshell.region.collapsed") val collapsed: Boolean = false,
    @PropLabel("widget.appshell.region.swipeToCollapse") val swipeToCollapse: Boolean = false,
    @PropLabel("widget.appshell.region.opacityPct") @PropRange(-1.0, 100.0) val opacityPct: Int = -1,
    @PropLabel("widget.appshell.region.blurDp") @PropRange(0.0, 40.0) val blurDp: Int = 0,
)

/**
 * Props for the LEFT region (the navigation rail). Like the right panel it renders its
 * own [NxSurface], at [CHROME_OPACITY_PCT] by default -- a see-through chrome that
 * prioritises the wallpaper behind it.
 */
@Serializable
data class ShellLeftRegionProps(
    @PropLabel("widget.appshell.region.widthDp") @PropRange(0.0, 600.0) val widthDp: Int = 0,
    @PropLabel("widget.appshell.region.showDivider") val showDivider: Boolean = false,
    @PropLabel("widget.appshell.region.collapsed") val collapsed: Boolean = false,
    @PropLabel("widget.appshell.region.opacityPct") @PropRange(-1.0, 100.0) val opacityPct: Int = CHROME_OPACITY_PCT,
    @PropLabel("widget.appshell.region.blurDp") @PropRange(0.0, 40.0) val blurDp: Int = 0,
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
    /** What the nav rail does: a fresh context rather than a push. See NavBackStack. */
    val onSwitchTab: (Screen) -> Unit,
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

@Composable
private fun RowScope.RegionDivider(show: Boolean) {
    if (show) VerticalDivider(Modifier.fillMaxHeight(), color = NxTheme.colors.outline)
}

// Shown for a collapsed region while editing: thin but visible, so the region's
// edit chrome (and its Tune affordance -- the only un-collapse path in edit
// mode) stays hoverable. A fully-returned region leaves nothing to hover.
@Composable
private fun CollapsedRegionStrip() {
    NxSurface(
        NxSurfaceLevel.Base, Modifier.width(22.dp).fillMaxHeight(), RectangleShape,
        borderWidthDp = 0f, opacity = 0.4f,
    ) {}
}

/**
 * Left region: the navigation rail plus the divider that separates it from the
 * center.
 */
@Widget(id = "appshell.region.left", displayName = "widget.appshell.region.left", propsClass = ShellLeftRegionProps::class)
@Composable
fun ShellLeftRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellLeftRegionProps>()
    if (props.collapsed) {
        if (LocalEditMode.current is EditModeState.On) CollapsedRegionStrip()
        return
    }
    val ctx = LocalShellContext.current
    Row(Modifier.fillMaxHeight()) {
        // The width lands on the rail, not on the row that also holds the divider.
        // On the row, the surface's weight(1f) took whatever the divider left, so a
        // named width came out a hairline short whenever the divider was on.
        val railWidth = if (props.widthDp > 0) Modifier.width(props.widthDp.dp) else Modifier.weight(1f)
        // The rail is an NxSurface at 35% by default. AppSidebar's NavigationRail is
        // transparent so this owns the background, and the divider stays OUTSIDE the
        // surface so the tinted area is exactly the rail. Light stops being forced
        // opaque here: a named opacity is a named opacity on either theme now.
        NxSurface(
            NxSurfaceLevel.Base, railWidth.fillMaxHeight(), RectangleShape,
            borderWidthDp = 0f,
            opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat(),
        ) {
            AppSidebar(
                currentScreen   = ctx.currentScreen,
                isAuthenticated = ctx.isAuthenticated,
                onScreenChange  = ctx.onScreenChange,
                onSwitchTab     = ctx.onSwitchTab,
                onLogout        = ctx.onLogout,
                modifier        = Modifier.fillMaxSize(),
            )
        }
        RegionDivider(props.showDivider)
    }
}

/**
 * Center region: the screen router. Carries weight=1 in the default layout so it
 * flexes between the two rails. Never collapsible: without it there is no
 * content area.
 */
@Widget(id = "appshell.region.center", displayName = "widget.appshell.region.center", propsClass = ShellCenterRegionProps::class)
@Composable
fun ShellCenterRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellCenterRegionProps>()
    // The wedge is the chrome reaching around the corner, so it takes the chrome's
    // colour rather than one of its own -- see [CHROME_OPACITY_PCT].
    val chrome = NxTheme.colors.surface.copy(alpha = CHROME_OPACITY_PCT / 100f)
    val cornerDp = LocalStyle.current.cardCorner
    NxSurface(
        NxSurfaceLevel.Base, Modifier.fillMaxSize(), RectangleShape,
        borderWidthDp = 0f,
        opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat(),
    ) {
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
        // Floating overlay lane. Inside the centre rather than at the window
        // root so anything docked here is bounded by the content column and
        // never rides over the rails, and above centerBody so it is not
        // scrolled away by whatever screen is mounted. It reserves no space:
        // an empty slot measures to nothing and an occupied one draws on top.
        SlotRenderer(SurfaceId(OVERLAY_SURFACE), SlotId("bottom"), Modifier.fillMaxSize())
    }
}

/** Sub-surface for widgets that float over the content rather than sit in it. */
private const val OVERLAY_SURFACE = "appshell.overlay"

private const val RAIL_COLLAPSED_GRAB = 0 // collapsed reserves no width -- it is not part of the layout; reopen via Ctrl+N / edit-mode Tune
private val AUTO_COLLAPSE_BELOW = 980.dp   // window narrower than this auto-collapses the right rail

/**
 * The right rail's width when nothing overrides it.
 *
 * Public because the shell insets its editor chrome by the same amount, and the
 * two were separate literals that happened to agree: a rail given another width
 * left the overlay measuring against a number nobody had updated.
 */
val RAIL_DEFAULT_WIDTH = 265.dp

/**
 * How far the panel sits off the window's edges: clear of the top bar, the bottom
 * and the content, flush to the right where the window ends.
 *
 * The inset and the rounding used to be a second plane the kernel drew around the
 * region, so the panel was a rounded card containing a square full-bleed one. One
 * plane draws both now. The radius is the style's panel corner rather than a number,
 * which is what it always was -- the old record stored 14, the value that token has
 * under Celestia, and a flat form had no way to square it.
 */
private val RAIL_INSET = 4.dp

/**
 * Right region: the divider plus the news panel. No handles or
 * strips: a horizontal swipe anywhere on the rail shuts it (the width tracks the
 * pointer and snaps on release; vertical scrolls and taps still reach the news).
 *
 * Collapsed it reserves no width at all -- see [RAIL_COLLAPSED_GRAB] -- so there is
 * nothing left on screen to swipe, and it reopens through Ctrl+N or, in edit mode,
 * the region's own Tune. This used to describe a slim catch at the edge; the catch
 * went to zero and the sentence outlived it. Edit mode keeps the static
 * prop-driven behaviour.
 */
@Widget(id = "appshell.region.right", displayName = "widget.appshell.region.right", propsClass = ShellRightRegionProps::class)
@Composable
fun ShellRightRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellRightRegionProps>()
    // The panel is an NxSurface at Floating depth: a SurfaceContainerHigh body (a step
    // up the tonal ladder from the page) plus a luminance-derived bevel, so it reads
    // as a distinct plane over any wallpaper and with none. Its opacity and blur are
    // the editable pair.
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

    val panelShape = RoundedCornerShape(LocalStyle.current.panelCorner)

    // Edit mode: static, no swipe/animation.
    if (editing) {
        if (props.collapsed) { CollapsedRegionStrip(); return }
        val sized = Modifier.width(if (props.widthDp > 0) props.widthDp.dp else RAIL_DEFAULT_WIDTH)
        NxSurface(
            NxSurfaceLevel.Floating,
            sized.fillMaxHeight().padding(start = RAIL_INSET, top = RAIL_INSET, bottom = RAIL_INSET).clip(panelShape),
            panelShape,
            opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat(),
        ) {
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
    val expandedWidth = if (props.widthDp > 0) props.widthDp.dp else RAIL_DEFAULT_WIDTH
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
            NxSurface(
                NxSurfaceLevel.Floating,
                Modifier.fillMaxSize().padding(start = RAIL_INSET, top = RAIL_INSET, bottom = RAIL_INSET).clip(panelShape),
                panelShape,
                opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat(),
            ) {
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
    @PropLabel("widget.appshell.topbar.opacityPct") @PropRange(-1.0, 100.0) val opacityPct: Int = CHROME_OPACITY_PCT,
    @PropLabel("widget.appshell.topbar.blurDp") @PropRange(0.0, 40.0) val blurDp: Int = 0,
    @PropLabel("widget.appshell.topbar.controls") val controls: WindowControlsMode = WindowControlsMode.Auto,
)

private const val TOPBAR_SURFACE = "appshell.topbar"

/**
 * Top region: the custom title bar. Replaces the OS chrome -- hosts the
 * breadcrumb / status widgets (the appshell.topbar sub-surface), a draggable
 * center lane, and the caption buttons. Caption buttons are chrome (not a
 * widget): placed left on macOS, right elsewhere, and shown per
 * [WindowControlsMode] (hidden by default on tiling WMs).
 */
@Widget(id = "appshell.region.top", displayName = "widget.appshell.region.top", propsClass = ShellTopRegionProps::class)
@Composable
fun ShellTopRegion(instance: WidgetInstance) {
    val props = instance.rememberProps<ShellTopRegionProps>()
    val windowState = LocalWindowState.current
    val composeWindow = LocalComposeWindow.current
    val maximizer = LocalWindowMaximizer.current
    val onClose = LocalChromeClose.current
    // With OS decorations (useCustomChrome off) the window already has caption
    // buttons + drag + resize, so the bar's chrome stands down; the breadcrumb
    // still renders (it is content, not window chrome).
    val useCustomChrome = LocalUseCustomChrome.current
    val showControls = useCustomChrome && props.controls.resolved() && windowState != null && maximizer != null
    // In edit mode the center is a widget drop-lane, not a window-drag zone --
    // otherwise dragging there moves the window instead of rearranging widgets.
    val editing = LocalEditMode.current is EditModeState.On

    val maximizeToggle: () -> Unit = { maximizer?.toggle() }
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

    @Composable
    fun Caption() {
        if (showControls) WindowControls(windowState, maximizer, onClose)
    }

    @Composable
    fun RowScope.DragLane() {
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .then(if (editing || !useCustomChrome) Modifier else Modifier.windowDragArea(composeWindow, maximizer, maximizeToggle)),
            contentAlignment = Alignment.Center,
        ) {
            SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("center"), spacing = 4.dp)
        }
    }

    // Padding BEFORE height, so Float's inset is a margin around the bar rather
    // than a bite out of it. After the height it was applied inside: a 44dp bar
    // asked for under Float drew 32, and the minimum 36 drew 24, with the height
    // control silently meaning two different things depending on the corner one.
    val barModifier = Modifier.fillMaxWidth().padding(outerPad).height(props.heightDp.dp)

    when (props.groupStyle) {
        GroupStyle.LineSeparated -> NxSurface(
            NxSurfaceLevel.Base, barModifier, shape,
            borderWidthDp = 0f,
            opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat(),
        ) {
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
            // The gutter exists to keep the pills off the window edge. Float already
            // holds them off it, and applying both put the row 12dp in horizontally
            // against 6dp vertically.
            barModifier.padding(horizontal = if (props.cornerStyle == CornerStyle.Float) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (HOST_IS_MAC && showControls) {
                NxSurface(NxSurfaceLevel.Base, Modifier.fillMaxHeight(), shape, borderWidthDp = 0f, opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat()) { Caption() }
            }
            AppGlyph()
            NxSurface(
                NxSurfaceLevel.Base, Modifier.fillMaxHeight(), shape,
                borderWidthDp = 0f,
                opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat(),
            ) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("left"), spacing = 4.dp)
                }
            }
            DragLane()
            NxSurface(
                NxSurfaceLevel.Base, Modifier.fillMaxHeight(), shape,
                borderWidthDp = 0f,
                opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat(),
            ) {
                Row(Modifier.fillMaxHeight().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SlotRenderer(SurfaceId(TOPBAR_SURFACE), SlotId("right"), spacing = 4.dp)
                }
            }
            if (!HOST_IS_MAC && showControls) {
                NxSurface(NxSurfaceLevel.Base, Modifier.fillMaxHeight(), shape, borderWidthDp = 0f, opacity = props.opacityPct.regionOpacity(), blurDp = props.blurDp.toFloat()) { Caption() }
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
 */
@Widget(id = "appshell.region.body", displayName = "widget.appshell.region.body")
@Composable
fun ShellBodyRegion() {
    SlotRenderer(SurfaceId("appshell.body"), SlotId("content"), Modifier.fillMaxSize())
}
