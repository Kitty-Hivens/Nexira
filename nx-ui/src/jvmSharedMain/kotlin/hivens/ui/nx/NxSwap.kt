package hivens.ui.nx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.Motion
import hivens.ui.theme.MotionRole
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxThemed

/**
 * Replaces content when [target] changes, animating the swap.
 *
 * Which transition that is belongs to the component, not to the screen hosting
 * it. Before this the router hand-placed a `Crossfade` and every other site
 * improvised its own pair of `fadeIn`/`slideIn` with durations to match -- the
 * same decision made again at each call, differently, and drifting apart as
 * screens were edited independently.
 *
 * The role carries both halves, so "this panel slides in" is one argument rather
 * than an assembled transition, and a style that asks for stillness collapses it
 * everywhere at once.
 *
 * [contentKey] says which targets are the same content: a change between two of
 * them updates the content in place instead of swapping it, so the two are never
 * drawn side by side. By default every distinct target is content of its own.
 */
@Composable
fun <T> NxSwap(
    target: T,
    modifier: Modifier = Modifier,
    role: MotionRole = Motion.fade,
    label: String = "nxSwap",
    contentKey: (T) -> Any? = { it },
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState    = target,
        modifier       = modifier,
        transitionSpec = { role.enter togetherWith role.exit },
        label          = label,
        contentKey     = contentKey,
    ) { value ->
        content(value)
    }
}

/**
 * Shows or hides [content] on the given role.
 *
 * The counterpart to [NxSwap] for the appear/disappear case: same reason, same
 * shape. A site that needs a transition the vocabulary does not describe still
 * builds one by hand, but it has to be a site that genuinely needs it rather
 * than the default way of doing this.
 */
@Composable
fun NxReveal(
    visible: Boolean,
    modifier: Modifier = Modifier,
    role: MotionRole = Motion.reveal,
    label: String = "nxReveal",
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible  = visible,
        modifier = modifier,
        enter    = role.enter,
        exit     = role.exit,
        label    = label,
        content  = content,
    )
}

@Preview
@Composable
private fun NxSwapDarkPreview() = SwapAndReveal(dark = true)

@Preview
@Composable
private fun NxSwapLightPreview() = SwapAndReveal(dark = false)

@Composable
private fun SwapAndReveal(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(320.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxSwap(target = 2) { page -> Text("Page $page of 5", color = NxInk.main) }
            NxSwap(target = "Slide", role = Motion.panelSlide) { Text("$it role", color = NxInk.main) }
            NxReveal(visible = true) { Text("Revealed content", color = NxInk.quiet) }
            NxReveal(visible = false) { Text("Hidden content", color = NxInk.quiet) }
        }
    }
}
