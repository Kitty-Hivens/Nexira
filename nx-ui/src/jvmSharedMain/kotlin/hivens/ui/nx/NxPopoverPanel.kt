package hivens.ui.nx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.layers.AnchoredLayer
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.surface.NxSurface
import hivens.ui.theme.Motion
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed
import hivens.ui.surface.SurfaceKind

/**
 * A titled panel that hangs off a control, for choices that do not fit a menu.
 *
 * [NxContextMenu] is a list of actions: one row, one verb, one click and it is
 * gone. A panel is the other shape -- several settings the user reads together,
 * changes in any order, and closes when they are done -- so it keeps a header, an
 * optional footer for the action that undoes all of it at once, and it does not
 * dismiss on a click inside.
 *
 * Declare it inside the `Box` that wraps the trigger. It does not hang BELOW the
 * trigger -- it grows OUT of it: the panel's corner lands on the trigger's corner
 * and it scales up from that point, so the control the user pressed becomes the
 * corner of what opened. Keep the trigger the same size as [NxIconButton]'s
 * default and the panel's own close lands exactly on it, which is what makes the
 * icon read as having turned into the close.
 *
 * Near the window bottom it grows upward instead, and the scale origin follows --
 * the provider measures where the panel actually landed and reports it back, so
 * the motion always starts at the trigger rather than at a corner that happens to
 * be nearest. The body scrolls once it outgrows [maxBodyHeight], so a panel can
 * carry more groups than the window is tall without its footer walking off screen.
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
    /** Where the focus goes when the panel closes, for a trigger that had it. */
    returnFocus: FocusRequester? = null,
    content: @Composable () -> Unit,
) {
    val states = remember { MutableTransitionState(false) }
    states.targetState = expanded
    // Shown through the exit animation, like the context menu: dropping the popup on
    // the frame the flag flips would cut the fade.
    val shown = states.currentState || states.targetState

    // Written by the position provider during layout and read by the transition --
    // the same handshake Material's own menu uses, because only the provider knows
    // which way the panel had to go to fit.
    val origin = remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val provider = remember(origin) { FromAnchorCorner(origin) }

    // Modal, like a menu: a press outside closes it and goes no further, and it
    // holds the keyboard while it is open. On its way out it blocks and closes nothing.
    AnchoredLayer(
        visible          = shown,
        position         = provider,
        modal            = expanded,
        focusable        = true,
        onDismissRequest = onDismissRequest.takeIf { expanded },
        returnFocus      = returnFocus,
    ) {
        AnimatedVisibility(
            visibleState = states,
            // Unfolds from the trigger rather than appearing beside it: the scale
            // starts near nothing at the corner the user pressed. The fade is the
            // quicker of the two so the panel reads as an object arriving, not as
            // text swelling into place.
            enter = fadeIn(Motion.tap.of()) +
                scaleIn(Motion.reveal.of(), initialScale = 0.10f, transformOrigin = origin.value),
            exit  = fadeOut(Motion.tap.of()) +
                scaleOut(Motion.tap.of(), targetScale = 0.10f, transformOrigin = origin.value),
        ) {
            NxSurface(
                kind     = SurfaceKind.Popup,
                shape    = MaterialTheme.shapes.medium,
                modifier = modifier.width(width),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // No inset on the end and none on top: the close sits exactly
                    // where the trigger is, so the panel's corner IS that control.
                    Row(
                        modifier          = Modifier.fillMaxWidth().padding(start = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text       = title,
                            style      = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color      = NxInk.main,
                            modifier   = Modifier.weight(1f),
                        )
                        NxIconButton(
                            icon               = NxIcon.Close,
                            contentDescription = null,
                            onClick            = onDismissRequest,
                        )
                    }
                    HorizontalDivider(color = NxInk.line)
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
                        HorizontalDivider(color = NxInk.line)
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
 * Puts the panel's corner on the trigger's corner and reports which corner that
 * turned out to be, so the growth starts there.
 *
 * Down and to the left by default. Near the bottom of the window it grows upward
 * instead -- the panel's bottom edge on the trigger's bottom edge -- and a panel
 * wider than the space to its left slides right, with the origin moved along it so
 * the motion still begins at the control.
 */
private class FromAnchorCorner(
    private val origin: MutableState<TransformOrigin>,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val growsDown = anchorBounds.top + popupContentSize.height <= windowSize.height
        val y = if (growsDown) {
            anchorBounds.top
        } else {
            (anchorBounds.bottom - popupContentSize.height).coerceAtLeast(0)
        }
        val originX = if (popupContentSize.width == 0) 1f
                      else ((anchorBounds.right - x).toFloat() / popupContentSize.width).coerceIn(0f, 1f)
        val originY = if (growsDown) 0f else 1f
        origin.value = TransformOrigin(originX, originY)
        return IntOffset(x, y)
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
            color = NxInk.quiet,
        )
        content()
        // Under the controls rather than over them: the caption names the axis, the
        // hint explains what the answer means, and it is read after the choice is
        // seen rather than before.
        hint?.let {
            Text(
                text  = it,
                style = MaterialTheme.typography.labelSmall,
                color = NxInk.quiet,
            )
        }
    }
}

@Preview
@Composable
private fun NxPopoverPanelDarkPreview() = OpenPopover(dark = true)

@Preview
@Composable
private fun NxPopoverPanelLightPreview() = OpenPopover(dark = false)

/**
 * The panel open on its trigger in a [LayerHost] that draws it in place. It unfolds when it
 * opens, so a static frame of the IDE may catch the start of that and the off-screen test
 * the end of it.
 */
@Composable
private fun OpenPopover(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        LayerHost(rememberWindowLayers(), Modifier.size(360.dp, 400.dp).background(NxColor.page)) {
            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                NxIconButton(NxIcon.Tune, "View options", onClick = {})
                NxPopoverPanel(
                    expanded         = true,
                    onDismissRequest = {},
                    title            = "View",
                    footer           = { NxButton("Reset", onClick = {}, style = NxButtonStyle.Tertiary, compact = true) },
                ) {
                    NxPanelGroup("Sort by") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NxChoiceChip("Name", selected = true, onToggle = {})
                            NxChoiceChip("Played", selected = false, onToggle = {})
                            NxChoiceChip("Size", selected = false, onToggle = {})
                        }
                    }
                    NxPanelGroup("Show", hint = "Hidden packs stay installed") {
                        NxToggle("Hidden packs", checked = false, onCheckedChange = {})
                    }
                    NxSlider("Card size", value = 0.5f, range = 0f..1f, valueText = "Medium", onValueChange = {}, compact = true)
                }
            }
        }
    }
}
