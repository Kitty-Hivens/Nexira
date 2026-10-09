package hivens.ui.nx

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.SchemeColours
import hivens.ui.theme.fit

/**
 * A list of answers to one question, hung off the control that asked it.
 *
 * The other shape beside [NxContextMenu]. A menu is a column of verbs, each its own
 * command, and reads best as small pills held off the menu's edge. A choice is one
 * question with several answers, and it took the menu's shape with a radio in front
 * of every row: a form inside a popup, small, with the answer in force marked by a
 * dot the eye has to find. Laid out after the catalogue's own listbox instead:
 * every answer a full-width row of its own height, the one in force filled with the
 * accent and named in it, the one under the pointer lifted, nothing in front of the
 * words.
 *
 * It hangs a little further off its trigger than a menu does and takes at least the
 * trigger's width, so it reads as the control opening rather than as something that
 * appeared near it. It unfolds from the trigger like a menu, through the same popup.
 *
 * [content] is a column of [NxChoiceItem]s, with [NxChoiceDivider] between groups.
 * [footer] stays out of the scroll, for a row that changes what the list holds.
 */
@Composable
fun NxChoiceMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    align: NxMenuAlign = NxMenuAlign.Start,
    minWidth: Dp = CHOICE_MIN_WIDTH,
    maxWidth: Dp = CHOICE_MAX_WIDTH,
    maxHeight: Dp = CHOICE_MAX_HEIGHT,
    footer: (@Composable () -> Unit)? = null,
    /** See [NxMenuPopup]: whether the list is on screen, its exit included, for the trigger's look. */
    onShownChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { CHOICE_GAP.roundToPx() }
    val origin = remember { mutableStateOf(TransformOrigin(0f, 0f)) }
    val anchorWidth = remember { mutableStateOf(0) }
    val provider = remember(gapPx, align, origin) { MenuBelowAnchor(align, gapPx, origin, anchorWidth) }
    val floor = with(density) { anchorWidth.value.toDp() }
    NxMenuPopup(provider, origin, expanded, onDismissRequest, onShownChange) {
        NxChoiceSurface(modifier, maxOf(minWidth, floor), maxOf(maxWidth, floor), maxHeight, footer, content)
    }
}

/**
 * The plane a choice list sits on: the popup surface at the larger corner, rows
 * running to its edges on every side, so the first and last answer's fill follows
 * the corner instead of being cut by it. A scrollbar only once there is something
 * to scroll.
 */
@Composable
internal fun NxChoiceSurface(
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

    NxSurface(kind = SurfaceKind.Popup, shape = MaterialTheme.shapes.large, modifier = modifier) {
        // Intrinsic width inside the bounds, for the reason the menu gives: a popup
        // arrives unbounded, and the rows only fill the same width once the column
        // has one.
        Column(Modifier.width(IntrinsicSize.Max).widthIn(min = minWidth, max = maxWidth)) {
            Column(
                Modifier
                    .hoverable(interaction)
                    .onGloballyPositioned { bodyHeight = with(density) { it.size.height.toDp() } }
                    .heightIn(max = maxHeight)
                    .verticalScroll(scroll),
            ) {
                content()
            }
            footer?.let {
                HorizontalDivider(color = NxInk.line)
                it()
            }
        }
        if (scroll.maxValue > 0 && bodyHeight > 0.dp) {
            NxVerticalScrollbar(
                adapter  = rememberScrollbarAdapter(scroll),
                revealed = hovered || scroll.isScrollInProgress,
                modifier = Modifier.align(Alignment.TopEnd).height(bodyHeight).padding(vertical = CHOICE_EDGE),
            )
        }
    }
}

/**
 * One answer. Filled with the accent and named in it while it is the one in force,
 * lifted under the pointer or the keyboard, and quiet otherwise. [hint] trails the
 * name in the muted ink, for what sets this answer apart from the rest: the build a
 * loader recommends, one that is still a beta.
 */
@Composable
fun NxChoiceItem(
    label: String,
    selected: Boolean,
    icon: IconKey? = null,
    hint: String? = null,
    enabled: Boolean = true,
    /** Reads as hovered without a pointer on it: where the keyboard is, in a list that has one. */
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lead = NxColor.lead()
    val lifted = (hovered || highlighted) && enabled
    val ground = when {
        selected && lifted -> NxColor.wash(lead, 0.24f)
        selected -> NxColor.wash(lead, 0.16f)
        lifted -> NxColor.wash(NxInk.quiet, 0.12f)
        else -> null
    }
    // Faded out of the wash's own hue rather than out of transparent black, which
    // the interpolation reads as a grey that flashes through every row the pointer
    // crosses on a light theme.
    val fill by animateColorAsState(
        ground ?: NxColor.wash(lead, 0f).copy(alpha = 0f),
        animationSpec = Motion.tap.of(),
        label = "choiceFill",
    )
    // The accent fitted to the wash it is set on, so the answer in force keeps its
    // contrast on any seed and under the pointer.
    val chosen = fit(lead, ground ?: NxColor.wash(lead, 0f), SchemeColours.TEXT)
    val ink by animateColorAsState(
        when {
            !enabled -> NxInk.off
            selected -> chosen
            lifted -> NxInk.main
            else -> NxInk.main.copy(alpha = 0.86f)
        },
        animationSpec = Motion.tap.of(),
        label = "choiceInk",
    )
    val check by animateFloatAsState(if (selected) 1f else 0f, animationSpec = Motion.tap, label = "choiceCheck")
    Row(
        Modifier
            .fillMaxWidth()
            .background(fill)
            .hoverable(interaction, enabled = enabled)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = CHOICE_ROW_H, vertical = CHOICE_ROW_V),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Symbol(icon, contentDescription = null, tint = ink, size = 18.dp)
            Spacer(Modifier.width(10.dp))
        }
        // The only weighted child, for the reason the menu row gives: two of them
        // double the intrinsic width the popup measures itself by.
        Text(
            text       = label,
            style      = MaterialTheme.typography.bodyMedium,
            color      = ink,
            fontWeight = FontWeight.SemiBold,
            maxLines   = 1,
            overflow   = TextOverflow.Ellipsis,
            modifier   = Modifier.weight(1f),
        )
        if (hint != null) {
            Spacer(Modifier.width(12.dp))
            // Fitted to the row's wash like the name is: the quiet ink is measured
            // against the popup's plane and loses contrast on the answer in force.
            Text(
                hint,
                style = MaterialTheme.typography.labelMedium,
                color = ground?.let { fit(NxInk.quiet, it, SchemeColours.TEXT) } ?: NxInk.quiet,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(10.dp))
        // Always laid out, faded in on the answer in force, so choosing does not
        // shift the row's text by the width of a mark.
        Box(Modifier.size(18.dp).alpha(check), contentAlignment = Alignment.Center) {
            Symbol(NxIcon.Check, contentDescription = null, tint = chosen, size = 18.dp)
        }
    }
}

/** A hairline between two groups of answers, running to the list's edges like the rows. */
@Composable
fun NxChoiceDivider() {
    HorizontalDivider(color = NxInk.line)
}

/**
 * A row in a choice list's footer that changes what the list holds, such as
 * snapshots in a version list. Set like an answer so the footer reads as part of
 * the list, in the quiet ink so it does not read as one.
 */
@Composable
fun NxChoiceFooterItem(label: String, icon: IconKey, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ink by animateColorAsState(if (hovered) NxInk.main else NxInk.quiet, animationSpec = Motion.tap.of(), label = "choiceFooterInk")
    Row(
        Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = CHOICE_ROW_H, vertical = CHOICE_ROW_V),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(icon, contentDescription = null, tint = ink, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The catalogue's listbox, measured: a row 44 tall with sixteen of side padding, the
 * list eight off its trigger, at least the trigger's width. Our own corner and ink.
 */
private val CHOICE_ROW_H = 16.dp
private val CHOICE_ROW_V = 12.dp
private val CHOICE_GAP = 8.dp
/** Where the scrollbar stops short of the rounded ends. */
private val CHOICE_EDGE = 6.dp
internal val CHOICE_MIN_WIDTH = 180.dp
private val CHOICE_MAX_WIDTH = 360.dp
private val CHOICE_MAX_HEIGHT = 320.dp
