package hivens.ui.nx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import kotlin.math.roundToInt

/** Which edge of the trigger the menu's own matching edge lands on. */
enum class NxMenuAlign {
    /** The menu's leading edge on the trigger's leading edge -- a list under a field. */
    Start,

    /** The menu's trailing edge on the trigger's trailing edge -- an overflow button. */
    End,
}

/**
 * How a row says whether it is the chosen one.
 *
 * [Check] is the menu idiom: a mark appears on the row that is already in force
 * and nothing is drawn on the others. [Radio] is the choice idiom: every row
 * carries an indicator, so the set reads as one question with several answers
 * before the pointer moves. A menu of verbs takes neither.
 */
enum class NxMenuMark { None, Check, Radio }

/** The gap between the trigger and the menu hanging off it. */
private val MENU_GAP = 4.dp

/** The inset from the surface edge to the rows, which carry their own padding. */
private val MENU_INSET = Spacing.s6

/**
 * Past this a menu is a column of sentences: the labels ellipsize instead, and
 * anything that needs the full text is a panel rather than a menu.
 */
private val MENU_MAX_WIDTH = 320.dp

/** Past this the menu scrolls rather than running off the window. */
private val MENU_MAX_HEIGHT = 320.dp

/**
 * Themed context menu -- replaces Material's `DropdownMenu`. Declare it inside the
 * `Box` that wraps the trigger; [content] is a column of [NxMenuItem]s.
 *
 * It grows out of the control that opened it: the position provider reports where
 * the menu actually landed and the scale starts from the trigger's own centre, so
 * a menu that had to flip above its button still unfolds from the button rather
 * than from whichever corner happens to be nearest. [align] picks which edge of
 * the trigger the menu lines up with -- trailing for an overflow button, leading
 * for a list under a field, where a right-aligned list reads as belonging to
 * something else.
 *
 * Size is bounded on both axes. A long label ellipsizes at [maxWidth] instead of
 * dragging the menu across the window, and past [maxHeight] the body scrolls, so a
 * list of sixty versions does not need the call site to hand-roll a scroll
 * container. [footer] stays out of that scroll: a row that switches what the list
 * contains has to be reachable without scrolling to the end of it.
 *
 * [matchAnchorWidth] makes the menu at least as wide as the trigger, which is what
 * a list under a text field has to be: a 120dp column of version numbers tucked
 * under a 400dp field reads as belonging to something else on the screen. The
 * trigger's width comes back from the position provider, the only place that knows
 * it, through the same handshake the origin uses.
 */
@Composable
fun NxContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    align: NxMenuAlign = NxMenuAlign.End,
    minWidth: Dp = 0.dp,
    maxWidth: Dp = MENU_MAX_WIDTH,
    maxHeight: Dp = MENU_MAX_HEIGHT,
    matchAnchorWidth: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { MENU_GAP.roundToPx() }
    val origin = remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val anchorWidth = remember { mutableStateOf(0) }
    val provider = remember(gapPx, align, origin, matchAnchorWidth) {
        MenuBelowAnchor(align, gapPx, origin, anchorWidth.takeIf { matchAnchorWidth })
    }
    val floor = if (matchAnchorWidth) with(density) { anchorWidth.value.toDp() } else 0.dp
    NxMenuPopup(provider, origin, expanded, onDismissRequest) {
        NxMenuSurface(modifier, maxOf(minWidth, floor), maxOf(maxWidth, floor), maxHeight, footer, content)
    }
}

/**
 * Cursor-anchored variant: opens with its top-left at [anchorInWindow] (window
 * coordinates), clamped on-screen and flipped above near the window bottom. For
 * right-click / "menu at the pointer" call sites. The unfold starts at the
 * pointer, wherever the menu had to be moved to fit.
 */
@Composable
fun NxContextMenu(
    anchorInWindow: Offset,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    minWidth: Dp = 0.dp,
    maxWidth: Dp = MENU_MAX_WIDTH,
    maxHeight: Dp = MENU_MAX_HEIGHT,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val gapPx = with(LocalDensity.current) { MENU_GAP.roundToPx() }
    val x = anchorInWindow.x.roundToInt()
    val y = anchorInWindow.y.roundToInt()
    val origin = remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val provider = remember(x, y, gapPx, origin) { MenuAtWindowOffset(x, y, gapPx, origin) }
    NxMenuPopup(provider, origin, expanded, onDismissRequest) {
        NxMenuSurface(modifier, minWidth, maxWidth, maxHeight, footer, content)
    }
}

/**
 * The popup shell: mounted through the exit animation, unfolding from wherever
 * [origin] says the trigger is.
 *
 * Shared with [NxSelect], which supplies its own body -- a select's list is data
 * rather than an arbitrary column, so it lays itself out lazily and drives the
 * keyboard, but it hangs off its trigger and dismisses exactly like a menu.
 */
@Composable
internal fun NxMenuPopup(
    provider: PopupPositionProvider,
    origin: MutableState<TransformOrigin>,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    body: @Composable () -> Unit,
) {
    val states = remember { MutableTransitionState(false) }
    states.targetState = expanded
    // Stay mounted through the exit animation: render while either the live or
    // the target state is still "open".
    if (!states.currentState && !states.targetState) return

    Popup(
        popupPositionProvider = provider,
        onDismissRequest      = onDismissRequest,
        properties            = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = states,
            // The fade is the quicker half so the menu reads as an object arriving
            // rather than as text swelling into place; the scale is the opening
            // role, which is what this is.
            enter = fadeIn(Motion.tap.of()) +
                scaleIn(Motion.reveal.of(), initialScale = 0.86f, transformOrigin = origin.value),
            exit  = fadeOut(Motion.tap.of()) +
                scaleOut(Motion.tap.of(), targetScale = 0.92f, transformOrigin = origin.value),
        ) {
            body()
        }
    }
}

@Composable
private fun NxMenuSurface(
    modifier: Modifier,
    minWidth: Dp,
    maxWidth: Dp,
    maxHeight: Dp,
    footer: (@Composable () -> Unit)?,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val density = LocalDensity.current
    var bodyHeight by remember { mutableStateOf(0.dp) }

    NxSurface(
        level   = NxSurfaceLevel.Floating,
        blurDp  = 0f,
        // Opaque: a menu floats over arbitrary content, so the dark-theme body
        // bleed-through (0.92) would read the rows underneath through it.
        opacity = 1f,
        shape   = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        // Intrinsic width outside the bounds, so the menu is as wide as its widest
        // row and no wider -- then clamped. It is also what makes a row's
        // fillMaxWidth mean anything: a popup arrives with an unbounded width
        // constraint, under which fillMaxWidth does nothing and every hover pill
        // would be a different length.
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = minWidth, max = maxWidth)) {
            Column(
                Modifier
                    .hoverable(interaction)
                    // Outermost of the three, so what it reports is the viewport and
                    // not the content inside it. Inside the scroll it measured the
                    // whole sixty-row column, the bar took that height, and the menu
                    // grew to several times the window.
                    .onGloballyPositioned { bodyHeight = with(density) { it.size.height.toDp() } }
                    .heightIn(max = maxHeight)
                    .verticalScroll(scroll)
                    .padding(MENU_INSET),
            ) {
                content()
            }
            footer?.let {
                HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
                Column(Modifier.padding(MENU_INSET)) { it() }
            }
        }
        // Outside the column above, and sized in dp rather than by filling it: a
        // scrollbar reports an infinite maximum intrinsic height, so one placed
        // inside a subtree measured with IntrinsicSize crashes the layout pass
        // outright. Only once there is something to scroll, too -- an always-present
        // bar over a five-row menu is a stripe with no meaning.
        if (scroll.maxValue > 0 && bodyHeight > 0.dp) {
            NxVerticalScrollbar(
                adapter  = rememberScrollbarAdapter(scroll),
                revealed = hovered || scroll.isScrollInProgress,
                modifier = Modifier.align(Alignment.TopEnd).height(bodyHeight).padding(vertical = MENU_INSET),
            )
        }
    }
}

/**
 * One menu row.
 *
 * The row is a pill inside the menu rather than a full-bleed band: a highlight
 * running to the surface edge cuts square corners into the menu's rounded ones at
 * the top and bottom row, which is the join every hand-rolled menu gets wrong.
 * Hover fades in rather than snapping, because a menu is a list the pointer
 * crosses and an instant swap on every row it passes reads as flicker.
 *
 * [mark] chooses how the row reports selection: a trailing check for a menu, a
 * leading radio for a list of answers to one question. The leading [icon] and
 * label share the row's colour so a [destructive] row reads red at a glance.
 *
 * [hint] trails the label in muted type -- for the keystroke that does the same
 * thing. A menu is where a shortcut is discovered: someone who reaches for the
 * menu is by definition someone who does not know the chord yet.
 */
@Composable
fun NxMenuItem(
    label: String,
    icon: IconKey? = null,
    destructive: Boolean = false,
    selected: Boolean = false,
    mark: NxMenuMark = NxMenuMark.Check,
    hint: String? = null,
    enabled: Boolean = true,
    /** Reads as hovered without a pointer on it -- where the keyboard is, in a list that has one. */
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val palette = NxTheme.colors
    val color = when {
        !enabled    -> palette.textSecondary.copy(alpha = 0.45f)
        destructive -> palette.error
        selected    -> palette.primary
        else        -> palette.textPrimary
    }
    val hoverTint = if (destructive) palette.error else palette.primary
    val wash by animateColorAsState(
        targetValue = when {
            (hovered || highlighted) && enabled -> hoverTint.copy(alpha = 0.14f)
            selected                            -> hoverTint.copy(alpha = 0.07f)
            else                                -> Color.Transparent
        },
        animationSpec = Motion.tap.of(),
        label         = "menuItemWash",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(wash)
            .hoverable(interaction, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.s10, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark == NxMenuMark.Radio) {
            RadioMark(selected = selected, color = if (selected) palette.primary else palette.textSecondary)
            Spacer(Modifier.width(Spacing.s10))
        }
        if (icon != null) {
            Symbol(icon, contentDescription = null, tint = color, size = 18.dp)
            Spacer(Modifier.width(Spacing.s10))
        }
        // Exactly one weighted child in the row, and it is the label. Two of them
        // (the label plus a spacer that pushed the hint over) doubled the row's
        // intrinsic width: Row's intrinsic pass takes the widest size-per-unit-of-
        // weight and multiplies it by the total weight, so a zero-width weighted
        // spacer beside a weighted label asks for two labels' worth of menu. The
        // label fills the slack itself, which right-aligns what follows it without
        // a second weight, and ellipsizes once the menu hits its width cap.
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val trailingCheck = selected && mark == NxMenuMark.Check
        // Fixed, so the intrinsic pass can see it: the gap between a label and the
        // shortcut beside it is part of how wide the menu has to be.
        if (hint != null || trailingCheck) Spacer(Modifier.width(Spacing.s16))
        if (hint != null) {
            Text(
                text  = hint,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
            )
            if (trailingCheck) Spacer(Modifier.width(Spacing.s8))
        }
        if (trailingCheck) {
            Symbol(NxIcon.Check, contentDescription = null, tint = color, size = 18.dp)
        }
    }
}

/**
 * The selection indicator for a list of answers, drawn rather than set in the icon
 * font: the dot has to grow out of the ring when the answer changes, and a glyph
 * swap cannot do that. Unselected it is an empty ring in the muted ink, so the
 * whole column reads as one question before anything is hovered.
 */
@Composable
internal fun RadioMark(selected: Boolean, color: Color, size: Dp = 16.dp) {
    val fill by animateFloatAsState(
        targetValue   = if (selected) 1f else 0f,
        animationSpec = Motion.tap.of(),
        label         = "radioMark",
    )
    Canvas(Modifier.size(size)) {
        val ring = this.size.minDimension / 2f
        drawCircle(
            color  = color.copy(alpha = 0.45f + 0.55f * fill),
            radius = ring - 0.75.dp.toPx(),
            style  = Stroke(width = 1.5.dp.toPx()),
        )
        if (fill > 0f) drawCircle(color = color, radius = (ring - 4.dp.toPx()) * fill)
    }
}

/**
 * A hairline between two groups of rows. Inset to the rows' own pill so it reads
 * as separating them rather than as cutting the menu in half.
 */
@Composable
fun NxMenuDivider() {
    HorizontalDivider(
        color    = NxTheme.colors.outline.copy(alpha = 0.25f),
        modifier = Modifier.padding(horizontal = Spacing.s10, vertical = Spacing.s6),
    )
}

/** A muted caption over a group of rows, for a menu that carries more than one kind of thing. */
@Composable
fun NxMenuSection(label: String) {
    Text(
        text     = label,
        style    = MaterialTheme.typography.labelSmall,
        color    = NxTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = Spacing.s10, end = Spacing.s10, top = Spacing.s6, bottom = Spacing.s4),
    )
}

/**
 * Hangs the menu off its trigger, on the [align] edge, and reports back where it
 * ended up so the unfold can start there.
 *
 * Below by default, above when the space below will not take it. The reported
 * origin is the trigger's own horizontal centre expressed in the menu's box, so a
 * narrow menu under a wide field grows from the middle of that field and not from
 * a corner of itself.
 */
internal class MenuBelowAnchor(
    private val align: NxMenuAlign,
    private val gapPx: Int,
    private val origin: MutableState<TransformOrigin>,
    /** Non-null when the caller wants the trigger's width reported back to it. */
    private val anchorWidth: MutableState<Int>? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        anchorWidth?.let { if (it.value != anchorBounds.width) it.value = anchorBounds.width }
        val rtl = layoutDirection == LayoutDirection.Rtl
        val leading = if (rtl) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val trailing = if (rtl) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val wanted = if (align == NxMenuAlign.Start) leading else trailing
        val x = wanted.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val growsDown = below + popupContentSize.height <= windowSize.height
        val y = if (growsDown) below else (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        origin.value = anchorOrigin(anchorBounds.center.x, x, popupContentSize.width, growsDown)
        return IntOffset(x, y)
    }
}

/**
 * Places the menu's top-left at a fixed window offset (the cursor), clamped into
 * the window and flipped above when it would overrun the bottom. Ignores
 * [anchorBounds] -- the position is the absolute window point the caller passed --
 * and reports the pointer itself as the origin.
 */
private class MenuAtWindowOffset(
    private val x: Int,
    private val y: Int,
    private val gapPx: Int,
    private val origin: MutableState<TransformOrigin>,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val px = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = y + gapPx
        val growsDown = below + popupContentSize.height <= windowSize.height
        val py = if (growsDown) below else (y - popupContentSize.height - gapPx).coerceAtLeast(0)
        origin.value = anchorOrigin(x, px, popupContentSize.width, growsDown)
        return IntOffset(px, py)
    }
}

/** Where the trigger sits inside the menu's own box, as a fraction on each axis. */
private fun anchorOrigin(anchorX: Int, popupX: Int, popupWidth: Int, growsDown: Boolean): TransformOrigin {
    val fx = if (popupWidth == 0) 0.5f else ((anchorX - popupX).toFloat() / popupWidth).coerceIn(0f, 1f)
    return TransformOrigin(fx, if (growsDown) 0f else 1f)
}
