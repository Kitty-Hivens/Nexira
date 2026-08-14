package hivens.ui.nx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hivens.ui.theme.Motion
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Themed context menu -- replaces Material's `DropdownMenu`. An opaque rounded
 * surface (legible under any style, including Brut / glassIntensity 0) that scales
 * + fades in. Declare it inside the `Box` that wraps the trigger; it anchors under
 * that box and flips above near the window bottom. [content] is a column of
 * [NxMenuItem]s.
 */
@Composable
fun NxContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val provider = remember(gapPx) { BelowAnchorEndAligned(gapPx) }
    NxContextMenuPopup(provider, expanded, onDismissRequest, content)
}

/**
 * Cursor-anchored variant: opens with its top-left at [anchorInWindow] (window
 * coordinates), clamped on-screen and flipped above near the window bottom. For
 * right-click / "menu at the pointer" call sites.
 */
@Composable
fun NxContextMenu(
    anchorInWindow: Offset,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val x = anchorInWindow.x.roundToInt()
    val y = anchorInWindow.y.roundToInt()
    val provider = remember(x, y, gapPx) { AtWindowOffset(x, y, gapPx) }
    NxContextMenuPopup(provider, expanded, onDismissRequest, content)
}

@Composable
private fun NxContextMenuPopup(
    provider: PopupPositionProvider,
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
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
            enter = fadeIn(Motion.tap.of()) + scaleIn(Motion.tap.of(), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit  = fadeOut(Motion.tap.of()) + scaleOut(Motion.tap.of(), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            NxSurface(
                level    = NxSurfaceLevel.Floating,
                glass    = false,
                // Opaque: a menu floats over arbitrary content, so the dark-theme
                // body bleed-through (0.92) would read the rows underneath through it.
                opaque   = true,
                shape    = MaterialTheme.shapes.medium,
                modifier = Modifier.width(IntrinsicSize.Max),
            ) {
                Column(Modifier.padding(vertical = Spacing.s6)) {
                    content()
                }
            }
        }
    }
}

/**
 * One menu row. Hover lifts a subtle accent (error tint for [destructive]); a
 * [selected] row reads in the accent with a trailing check. The leading [icon] and
 * label share the row's colour so a destructive row reads red at a glance.
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
    hint: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = when {
        destructive -> NxTheme.colors.error
        selected    -> NxTheme.colors.primary
        else        -> NxTheme.colors.textPrimary
    }
    val hoverTint = if (destructive) NxTheme.colors.error else NxTheme.colors.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(if (hovered) hoverTint.copy(alpha = 0.14f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Symbol(icon, contentDescription = null, tint = color, size = 18.dp)
            Spacer(Modifier.width(Spacing.s10))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
        if (hint != null || selected) Spacer(Modifier.weight(1f))
        if (hint != null) {
            Text(
                text  = hint,
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary,
            )
            if (selected) Spacer(Modifier.width(Spacing.s8))
        }
        if (selected) {
            Symbol(NxIcon.Check, contentDescription = null, tint = color, size = 18.dp)
        }
    }
}

/**
 * Anchors the menu's right edge to the trigger's right edge (so a right-aligned
 * overflow button opens leftwards and stays on screen), just below it -- flipping
 * above when it would overrun the window bottom.
 */
// Shared with NxPopoverPanel: a panel hangs off its trigger the same way a menu
// does, and two copies of the flip-near-the-bottom rule would drift.
internal class BelowAnchorEndAligned(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

/**
 * Places the menu's top-left at a fixed window offset (the cursor), clamped into the
 * window and flipped above when it would overrun the bottom. Ignores [anchorBounds]
 * -- the position is the absolute window point the caller passed.
 */
private class AtWindowOffset(
    private val x: Int,
    private val y: Int,
    private val gapPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val px = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = y + gapPx
        val py = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (y - popupContentSize.height - gapPx).coerceAtLeast(0)
        }
        return IntOffset(px, py)
    }
}
