package hivens.ui.nx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import hivens.ui.icons.NxIcon
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme

/**
 * A titled panel that hangs off a control, for choices that do not fit a menu.
 *
 * [NxContextMenu] is a list of actions: one row, one verb, one click and it is
 * gone. A panel is the other shape -- several settings the user reads together,
 * changes in any order, and closes when they are done -- so it keeps a header, an
 * optional footer for the action that undoes all of it at once, and it does not
 * dismiss on a click inside.
 *
 * Declare it inside the `Box` that wraps the trigger; it anchors under that box,
 * end-aligned, and flips above near the window bottom. The body scrolls once it
 * outgrows [maxBodyHeight], so a panel can carry more groups than the window is
 * tall without the footer walking off screen.
 */
@Composable
fun NxPopoverPanel(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = 268.dp,
    maxBodyHeight: Dp = 420.dp,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val states = remember { MutableTransitionState(false) }
    states.targetState = expanded
    // Mounted through the exit animation, like the context menu: dropping the
    // popup on the frame the flag flips would cut the fade.
    if (!states.currentState && !states.targetState) return

    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val provider = remember(gapPx) { BelowAnchorEndAligned(gapPx) }

    Popup(
        popupPositionProvider = provider,
        onDismissRequest      = onDismissRequest,
        properties            = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = states,
            enter = fadeIn(Motion.tap.of()) + scaleIn(Motion.tap.of(), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
            exit  = fadeOut(Motion.tap.of()) + scaleOut(Motion.tap.of(), targetScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            NxSurface(
                level    = NxSurfaceLevel.Floating,
                glass    = false,
                // Opaque for the same reason a menu is: it floats over arbitrary
                // content, and the dark-theme body bleed would read the list
                // underneath through the panel.
                opaque   = true,
                shape    = MaterialTheme.shapes.medium,
                modifier = modifier.width(width),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text       = title,
                            style      = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color      = NxTheme.colors.textPrimary,
                            modifier   = Modifier.weight(1f),
                        )
                        NxIconButton(
                            icon               = NxIcon.Close,
                            contentDescription = null,
                            onClick            = onDismissRequest,
                            iconSize           = 16.dp,
                        )
                    }
                    HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxBodyHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        content()
                    }
                    footer?.let {
                        HorizontalDivider(color = NxTheme.colors.outline.copy(alpha = 0.25f))
                        Row(
                            modifier              = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) { it() }
                    }
                }
            }
        }
    }
}

/**
 * One labelled group inside a panel: a muted caption over its controls. Groups are
 * what make a panel readable at a glance -- a flat stack of switches is a list of
 * settings, not a set of questions.
 */
@Composable
fun NxPanelGroup(
    label: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = NxTheme.colors.textSecondary,
        )
        content()
        // Under the controls rather than over them: the caption names the axis, the
        // hint explains what the answer means, and it is read after the choice is
        // seen rather than before.
        hint?.let {
            Text(
                text  = it,
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary.copy(alpha = 0.7f),
            )
        }
    }
}
