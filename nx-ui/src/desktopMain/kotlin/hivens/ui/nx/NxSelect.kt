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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.theme.Motion
import hivens.ui.theme.Spacing
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.surface.SurfaceKind

/**
 * One choice out of a known set, as a field that opens the set beneath itself.
 *
 * The difference from [NxChoiceMenu] is that the options are DATA here, not an
 * arbitrary column of rows, and everything a list can only do when it knows its
 * own contents follows from that: the list opens scrolled to the answer already in
 * force and the keyboard walks it. The rows are [NxChoiceItem]s either way, so a
 * select and a hand-built choice list read as the same control.
 *
 * It is the shape a settings row, a property editor or a sort control wants. A
 * menu of verbs is another shape and stays on [NxContextMenu]. Several settings
 * read together are a third and belong in [NxPopoverPanel].
 *
 * The trigger names the answer in force in the strong ink, with [prefix] before it
 * in the muted one when the question is not already named beside the control. The
 * list hangs a little off the trigger, at least as wide as it, and unfolds from it,
 * upward from its top edge when the space below will not take it.
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
    /** Muted words before the answer, naming the question: "Sort by". */
    prefix: String? = null,
    /** A muted word after an answer in the list, for what sets it apart. */
    hint: (T) -> String? = { null },
    maxHeight: Dp = 320.dp,
    /** A row under the list that changes what it holds, see [NxChoiceFooterItem]. */
    footer: (@Composable () -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // Up for as long as any of the list is on screen, see [NxMenuPopup].
    var shown by remember { mutableStateOf(false) }
    var triggerWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lead = NxColor.lead()
    val line = NxInk.line

    // The caret turns over rather than swapping to a second glyph: one object
    // moving says "this opened" where two glyphs say "something changed".
    val caret by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = Motion.reveal,
        label = "selectCaret",
    )
    val edge by animateColorAsState(
        targetValue = when {
            !enabled -> line.copy(alpha = 0.5f)
            expanded -> lead
            hovered || shown -> lead.copy(alpha = 0.55f)
            else     -> line
        },
        animationSpec = Motion.colorShift.of(),
        label = "selectEdge",
    )

    Box(modifier) {
        NxSurface(
            kind              = SurfaceKind.Field,
            shape             = MaterialTheme.shapes.medium,
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
            // Inside the field, so the inks are the ones that read on it.
            val ink = if (enabled) NxInk.main else NxInk.off
            Row(
                modifier          = Modifier.fillMaxWidth().padding(start = Spacing.s12, end = Spacing.s8, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                prefix?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = NxInk.quiet, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.width(Spacing.s6))
                }
                selected?.let(icon)?.let {
                    Symbol(it, contentDescription = null, tint = ink, size = 18.dp)
                    Spacer(Modifier.width(Spacing.s8))
                }
                Text(
                    text       = selected?.let(label) ?: placeholder,
                    style      = MaterialTheme.typography.bodyMedium,
                    color      = if (selected == null) NxInk.quiet else ink,
                    fontWeight = if (selected == null) FontWeight.Normal else FontWeight.SemiBold,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    // Filling, so the caret sits at the trigger's far edge however
                    // short the answer is, where the eye looks for "this opens".
                    modifier   = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.s8))
                Symbol(
                    icon               = NxIcon.ExpandMore,
                    contentDescription = null,
                    tint               = if (expanded) NxColor.lead(text = true) else NxInk.quiet,
                    size               = 20.dp,
                    modifier           = Modifier.graphicsLayer { rotationZ = caret },
                )
            }
        }

        val gapPx = with(density) { 8.dp.roundToPx() }
        val origin = remember { mutableStateOf(TransformOrigin(0f, 0f)) }
        val provider = remember(gapPx, origin) { MenuBelowAnchor(NxMenuAlign.Start, gapPx, origin) }
        NxMenuPopup(provider, origin, expanded, { expanded = false }, { shown = it }) {
            SelectList(
                options   = options,
                selected  = selected,
                label     = label,
                icon      = icon,
                hint      = hint,
                width     = triggerWidth,
                maxHeight = maxHeight,
                footer    = footer,
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
    hint: (T) -> String?,
    width: Dp,
    maxHeight: Dp,
    footer: (@Composable () -> Unit)?,
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
    // Keyed on the list: a footer that changes what the list holds (snapshots shown
    // or hidden) does so under an open popup, and an index kept from the old list
    // pointed past the end of the new one or at somebody else's row.
    var active by remember(options) { mutableStateOf(selectedIndex.coerceAtLeast(0)) }

    LaunchedEffect(options) {
        if (selectedIndex >= 0) listState.scrollToItem(selectedIndex)
        focus.requestFocus()
    }
    LaunchedEffect(active) { listState.revealItem(active) }

    NxSurface(
        kind     = SurfaceKind.Popup,
        shape    = MaterialTheme.shapes.large,
        modifier = Modifier
            .widthIn(min = CHOICE_MIN_WIDTH)
            .width(if (width > 0.dp) width else CHOICE_MIN_WIDTH)
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
        Column {
            Box(Modifier.hoverable(hoverSource)) {
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = maxHeight)) {
                    itemsIndexed(options) { index, option ->
                        NxChoiceItem(
                            label       = label(option),
                            selected    = option == selected,
                            icon        = icon(option),
                            hint        = hint(option),
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
            footer?.let {
                HorizontalDivider(color = NxInk.line)
                it()
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
