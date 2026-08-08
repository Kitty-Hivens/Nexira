package hivens.ui.nx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.ui.theme.Motion
import hivens.ui.theme.MotionRole

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
 */
@Composable
fun <T> NxSwap(
    target: T,
    modifier: Modifier = Modifier,
    role: MotionRole = Motion.fade,
    label: String = "nxSwap",
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState    = target,
        modifier       = modifier,
        transitionSpec = { role.enter togetherWith role.exit },
        label          = label,
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
