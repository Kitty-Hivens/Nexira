package hivens.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.clip
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
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme

/**
 * Themed context menu, in the app's glass language -- replaces Material's
 * `DropdownMenu`, which ships a flat opaque surface that clashes with every
 * other floating panel here. A translucent rounded card with a hairline outline,
 * scaling + fading in from the trigger's top-right corner. Declare it inside the
 * `Box` that wraps the trigger; it anchors under that box and flips above near
 * the screen bottom. [content] is a column of [NxMenuItem]s.
 */
@Composable
fun NxContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    val states = remember { MutableTransitionState(false) }
    states.targetState = expanded
    // Stay mounted through the exit animation: render while either the live or
    // the target state is still "open".
    if (!states.currentState && !states.targetState) return

    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val provider = remember(gapPx) { BelowAnchorEndAligned(gapPx) }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest      = onDismissRequest,
        properties            = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = states,
            enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit  = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(glassSurfaceAlpha(0.9f))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .border(1.dp, NxTheme.colors.outline.copy(alpha = 0.25f), MaterialTheme.shapes.medium)
                    .width(IntrinsicSize.Max)
                    .padding(vertical = 6.dp),
            ) {
                content()
            }
        }
    }
}

/**
 * One menu row. Hover lifts a subtle accent (error tint for [destructive]); the
 * leading [icon] and label share the row's colour so a destructive row reads red
 * at a glance.
 */
@Composable
fun NxMenuItem(
    label: String,
    icon: IconKey? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color = if (destructive) NxTheme.colors.error else NxTheme.colors.textPrimary
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
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * Anchors the menu's right edge to the trigger's right edge (so a right-aligned
 * overflow button opens leftwards and stays on screen), just below it -- flipping
 * above when it would overrun the window bottom.
 */
private class BelowAnchorEndAligned(private val gapPx: Int) : PopupPositionProvider {
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
