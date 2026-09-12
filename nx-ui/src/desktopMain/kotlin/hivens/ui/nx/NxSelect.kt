package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing

/** Narrower than this a list of answers is unreadable, whatever the trigger measures. */
private val SELECT_MIN_WIDTH = 160.dp

/**
 * One choice out of a known set, as a field that opens the set beneath itself.
 *
 * The difference from [NxContextMenu] is that the options are DATA here, not an
 * arbitrary column of rows, and everything a list can only do when it knows its
 * own contents follows from that: the list opens scrolled to the answer already in
 * force, the keyboard walks it, and every row carries a radio so the column reads
 * as one question rather than as a stack of unrelated commands.
 *
 * It is the shape a settings row or a property editor wants. A menu of verbs is
 * the other shape and stays on [NxContextMenu]; several settings read together are
 * a third and belong in [NxPopoverPanel].
 *
 * The list takes the trigger's own width and hangs off its leading edge, so it
 * reads as the field opening rather than as a menu that happens to be nearby, and
 * it unfolds from the field -- upward, from the field's bottom edge, when the
 * space below will not take it.
 */
@Composable
fun <T> NxSelect(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    icon: (T) -> IconKey? = { null },
    maxHeight: Dp = 320.dp,
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val palette = NxTheme.colors

    // The caret turns over rather than swapping to a second glyph: one object
    // moving says "this opened" where two glyphs say "something changed".
    val caret by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.reveal,
        label = "selectCaret",
    )
    val edge by animateColorAsState(
        targetValue = when {
            !enabled -> palette.outline.copy(alpha = 0.18f)
            expanded -> palette.primary
            hovered  -> palette.primary.copy(alpha = 0.55f)
            else     -> palette.outline.copy(alpha = 0.35f)
        },
        animationSpec = Motion.colorShift.of(),
        label = "selectEdge",
    )
    val ink = if (enabled) palette.textPrimary else palette.textSecondary.copy(alpha = 0.5f)

    Box(modifier) {
        NxSurface(
            level             = NxSurfaceLevel.Sunken,
            blurDp            = 0f,
            shape             = MaterialTheme.shapes.small,
            borderColor       = edge,
            interactionSource = interaction,
            modifier          = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { triggerWidth = with(density) { it.size.width.toDp() } }
                .hoverable(interaction, enabled = enabled)
                .clickable(
                    interactionSource = interaction,
                    indication        = null,
                    enabled           = enabled,
                    onClick           = { expanded = !expanded },
                ),
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(horizontal = Spacing.s10, vertical = Spacing.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                selected?.let(icon)?.let {
                    Symbol(it, contentDescription = null, tint = ink, size = 18.dp)
                    Spacer(Modifier.width(Spacing.s8))
                }
                Text(
                    text     = selected?.let(label) ?: placeholder,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = if (selected == null) palette.textSecondary.copy(alpha = 0.7f) else ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.s8))
                Symbol(
                    icon               = NxIcon.ArrowDropDown,
                    contentDescription = null,
                    tint               = if (expanded) palette.primary else palette.textSecondary,
                    size               = 18.dp,
                    modifier           = Modifier.graphicsLayer { rotationZ = caret },
                )
            }
        }

        val gapPx = with(density) { 4.dp.roundToPx() }
        val origin = remember { mutableStateOf(TransformOrigin(0f, 0f)) }
        val provider = remember(gapPx, origin) { MenuBelowAnchor(NxMenuAlign.Start, gapPx, origin) }
        NxMenuPopup(provider, origin, expanded, { expanded = false }) {
            SelectList(
                options   = options,
                selected  = selected,
                label     = label,
                icon      = icon,
                width     = triggerWidth,
                maxHeight = maxHeight,
                onPick    = { expanded = false; onSelect(it) },
                onDismiss = { expanded = false },
            )
        }
    }
}

@Composable
private fun <T> SelectList(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    icon: (T) -> IconKey?,
    width: Dp,
    maxHeight: Dp,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val focus = remember { FocusRequester() }
    val selectedIndex = options.indexOfFirst { it == selected }
    // Where the keyboard is. Starts on the answer in force, so the first arrow key
    // steps off it rather than jumping to the top of a list the user is already in.
    var active by remember { mutableStateOf(selectedIndex.coerceAtLeast(0)) }

    LaunchedEffect(Unit) {
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
        focus.requestFocus()
    }
    LaunchedEffect(active) { listState.revealItem(active) }

    NxSurface(
        level    = NxSurfaceLevel.Floating,
        blurDp   = 0f,
        opacity  = 1f,
        shape    = MaterialTheme.shapes.medium,
        modifier = Modifier
            .widthIn(min = SELECT_MIN_WIDTH)
            .width(if (width > 0.dp) width else SELECT_MIN_WIDTH)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> { active = (active + 1).coerceAtMost(options.lastIndex); true }
                    Key.DirectionUp   -> { active = (active - 1).coerceAtLeast(0); true }
                    Key.MoveHome      -> { active = 0; true }
                    Key.MoveEnd       -> { active = options.lastIndex; true }
                    Key.Enter, Key.NumPadEnter -> {
                        options.getOrNull(active)?.let(onPick) ?: onDismiss()
                        true
                    }
                    Key.Escape -> { onDismiss(); true }
                    else       -> false
                }
            },
    ) {
        Box(Modifier.hoverable(hoverSource)) {
            LazyColumn(
                state          = listState,
                modifier       = Modifier.heightIn(max = maxHeight),
                contentPadding = PaddingValues(Spacing.s6),
            ) {
                itemsIndexed(options) { index, option ->
                    NxMenuItem(
                        label       = label(option),
                        icon        = icon(option),
                        selected    = option == selected,
                        mark        = NxMenuMark.Radio,
                        highlighted = index == active,
                        onClick     = { onPick(option) },
                    )
                }
            }
            if (listState.canScrollForward || listState.canScrollBackward) {
                // In a wrapper that matches the list rather than filling the box: a
                // popup arrives with an unbounded height constraint, so fillMaxHeight
                // on the bar itself asks for an infinite one.
                Box(Modifier.matchParentSize()) {
                    NxVerticalScrollbar(
                        adapter  = rememberScrollbarAdapter(listState),
                        revealed = hovered || listState.isScrollInProgress,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = Spacing.s6),
                    )
                }
            }
        }
    }
}

/**
 * Brings [index] fully into view, and only as far as that.
 *
 * `animateScrollToItem` would put every keyboard step at the top of the viewport,
 * which turns walking a list into the list walking under a fixed cursor. A row
 * already visible is left where it is; one hanging off an edge is nudged in by
 * exactly its overhang.
 */
private suspend fun LazyListState.revealItem(index: Int) {
    if (index < 0) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        ?: run { animateScrollToItem(index); return }
    val overshootTop = info.viewportStartOffset - item.offset
    val overshootBottom = item.offset + item.size - info.viewportEndOffset
    when {
        overshootTop > 0    -> animateScrollBy(-overshootTop.toFloat())
        overshootBottom > 0 -> animateScrollBy(overshootBottom.toFloat())
    }
}
