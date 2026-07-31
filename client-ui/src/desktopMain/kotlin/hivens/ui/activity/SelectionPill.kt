package hivens.ui.activity

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.core.activity.Activity
import hivens.ui.i18n.AppStrings
import hivens.ui.icons.NxIcon
import hivens.ui.nx.NxButton
import hivens.ui.nx.NxButtonStyle
import hivens.ui.nx.NxFit
import hivens.ui.nx.NxIconButton
import hivens.ui.nx.NxTooltip
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

/**
 * The same object, narrating what the user has picked instead of what the
 * launcher is doing.
 *
 * It is a separate composable rather than a branch inside the activity body
 * because the two share a shell and nothing else: one counts things and offers
 * verbs, the other names one thing and measures it. Threading both through one
 * row would mean a column of conditionals where every zone means two things.
 *
 * [ambient] is whatever the launcher is doing underneath, if anything. Its face
 * stays at the leading edge so the work is not hidden by the selection, and it
 * is the way back: dropping the selection hands the body to it again.
 */
@Composable
internal fun SelectionPill(
    selection: Selection,
    ambient: Activity?,
    props: PillProps,
    s: AppStrings,
    maxWidth: Dp,
    open: Boolean = true,
) {
    val style = LocalStyle.current
    val colors = NxTheme.colors
    val height = props.heightDp.dp
    val corner by animateDpAsState(
        targetValue = if (open) style.panelCorner else height / 2,
        animationSpec = tween(style.animationDurationMs(380)),
        label = "selectionCorner",
    )
    val shape = RoundedCornerShape(corner)

    NxSurface(
        level = NxSurfaceLevel.Floating,
        modifier = Modifier
            .heightIn(min = height)
            .widthIn(max = maxWidth)
            .animateContentSize(tween(style.animationDurationMs(380)))
            .clip(shape),
        shape = shape,
        tier = props.frostTier,
        elevated = true,
        opaque = true,
    ) {
        Row(
            // No fillMaxWidth: it stretched the object to the ceiling and, once
            // the weighted spacer was gone, left everything packed against the
            // left edge of a mostly empty bar. The object is as wide as its row.
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionStack(selection.items, ambient)
            if (!open) return@Row
            // Count, rule, clear: one tight cluster about what is picked. The rule
            // belongs inside it, separating the fact from the way to undo it --
            // parked between the cluster and the verbs it left the clear button
            // stranded in the middle of the object's empty half.
            Text(
                text = s.selectionCount(selection.items.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            VerticalDivider(Modifier.height(20.dp), color = colors.outline)
            NxButton(
                label = s.selectionClear,
                onClick = selection.clear,
                style = NxButtonStyle.Tertiary,
                icon = NxIcon.Close,
                compact = true,
            )
            // An explicit gap, not a weighted one. The object is as wide as what it
            // holds, so there is no free space for a weight to spread -- and with
            // nothing between them the verbs sat against the clear control and the
            // whole row read as one undifferentiated run of buttons.
            if (props.showActions && selection.actions.isNotEmpty()) {
                Spacer(Modifier.width(CLUSTER_GAP))
                selection.actions.forEach { action ->
                    NxTooltip(text = action.blockedReason.orEmpty(), enabled = action.blockedReason != null) {
                        NxButton(
                            label = action.kind.label(s),
                            onClick = action.run,
                            style = if (action.kind == SelectionActionKind.Delete) {
                                NxButtonStyle.Destructive
                            } else {
                                NxButtonStyle.Tertiary
                            },
                            icon = action.kind.icon(),
                            enabled = action.blockedReason == null,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}

/** Count, the way to undo it, and the verbs -- all at one level of detail. */
@Composable
private fun Body(selection: Selection, s: AppStrings, props: PillProps, labelled: Boolean) {
    val colors = NxTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (labelled) 12.dp else 6.dp),
    ) {
        Text(
            text = s.selectionCount(selection.items.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        VerticalDivider(Modifier.height(20.dp), color = colors.outline)
        if (labelled) {
            NxButton(
                label = s.selectionClear,
                onClick = selection.clear,
                style = NxButtonStyle.Tertiary,
                icon = NxIcon.Close,
                compact = true,
            )
        } else {
            NxTooltip(text = s.selectionClear) {
                NxIconButton(NxIcon.Close, s.selectionClear, selection.clear)
            }
        }
        if (props.showActions && selection.actions.isNotEmpty()) {
            Spacer(Modifier.width(if (labelled) CLUSTER_GAP else 8.dp))
            VerticalDivider(Modifier.height(26.dp), color = colors.outline)
            Verbs(selection, s, labelled)
        }
    }
}

/**
 * The verbs, with or without their labels. Without, the name moves into the
 * tooltip, so a control that has shrunk still says what it is -- and a blocked
 * one still says why.
 */
@Composable
private fun Verbs(selection: Selection, s: AppStrings, labelled: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (labelled) 12.dp else 4.dp),
    ) {
        selection.actions.forEach { action ->
            val name = action.kind.label(s)
            val hint = action.blockedReason ?: name
            NxTooltip(text = hint, enabled = action.blockedReason != null || !labelled) {
                if (labelled) {
                    NxButton(
                        label = name,
                        onClick = action.run,
                        style = if (action.kind == SelectionActionKind.Delete) {
                            NxButtonStyle.Destructive
                        } else {
                            NxButtonStyle.Tertiary
                        },
                        icon = action.kind.icon(),
                        enabled = action.blockedReason == null,
                        compact = true,
                    )
                } else {
                    NxIconButton(
                        icon = action.kind.icon(),
                        contentDescription = name,
                        onClick = action.run,
                        enabled = action.blockedReason == null,
                    )
                }
            }
        }
    }
}

/** The picked things, with the launcher's own work still visible at the lead. */
@Composable
private fun SelectionStack(items: List<SelectionItem>, ambient: Activity?) {
    Box(contentAlignment = Alignment.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(-8.dp)) {
            items.take(3).reversed().forEach { StackFace(it.key, it.title, it.icon) }
            val hidden = items.size - minOf(items.size, 3)
            if (hidden > 0) StackOverflow(hidden)
            // The launcher's own work, still present while the selection holds the
            // body. Nothing is lost; it is simply not the sentence.
            ambient?.let { StackFace(it.key, it.title, it.iconUrl) }
        }
    }
}

private fun SelectionActionKind.label(s: AppStrings): String = when (this) {
    SelectionActionKind.Enable -> s.selectionEnable
    SelectionActionKind.Disable -> s.selectionDisable
    SelectionActionKind.Delete -> s.selectionDelete
}

private fun SelectionActionKind.icon() = when (this) {
    SelectionActionKind.Enable -> NxIcon.Check
    SelectionActionKind.Disable -> NxIcon.VisibilityOff
    SelectionActionKind.Delete -> NxIcon.Delete
}

/** Space between what is picked and what can be done to it. */
private val CLUSTER_GAP = 20.dp
