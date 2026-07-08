package hivens.ui.nx

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme

// One look for every list bar (Rule 0). Geometry and the 40%/75% textSecondary mix
// match the console's hand-tuned bar; the only addition here is the idle fade. (The
// console still builds its own style inline -- folding it onto this primitive is the
// follow-up that makes the shared look enforced rather than merely copied.)
private val Thickness = 8.dp
private val MinLength = 24.dp
private const val IdleFadeDelayMs  = 600   // browser-like pause before the bar idles out
private const val FadeInMs         = 120
private const val FadeOutMs        = 400
private const val HoverCrossfadeMs = 250   // thumb unhover->hover, matches the console bar

/**
 * Auto-hiding vertical scrollbar. Idle it is fully faded out; while [revealed] it fades
 * in, then idles back out after a short pause -- the way a browser overlay bar behaves.
 *
 * [revealed] is the caller's composed "show it" signal, typically
 * `hovered || listState.isScrollInProgress`. It stays out of here on purpose so the bar
 * is agnostic to the scrollable's state type (LazyListState, ScrollState, ...); the
 * container owns the hover source, the state owns the scroll flag.
 */
@Composable
fun NxVerticalScrollbar(
    adapter: ScrollbarAdapter,
    revealed: Boolean,
    modifier: Modifier = Modifier,
) = VerticalScrollbar(adapter = adapter, modifier = modifier, style = autoHideStyle(revealed))

/** Horizontal peer of [NxVerticalScrollbar]; same fade contract. */
@Composable
fun NxHorizontalScrollbar(
    adapter: ScrollbarAdapter,
    revealed: Boolean,
    modifier: Modifier = Modifier,
) = HorizontalScrollbar(adapter = adapter, modifier = modifier, style = autoHideStyle(revealed))

@Composable
private fun autoHideStyle(revealed: Boolean): ScrollbarStyle {
    val base  = NxTheme.colors.textSecondary
    val style = LocalStyle.current
    // Reveal fades in at once; hiding waits out the idle pause, then fades. The fade and
    // the thumb's hover crossfade route through the style so reduced-motion collapses
    // them; the idle pause is fixed UX pacing, not motion, so it stays put. The corner
    // follows buttonCorner so a Brut UI gets a square bar, not a lone rounded one.
    val alpha by animateFloatAsState(
        targetValue   = if (revealed) 1f else 0f,
        animationSpec = tween(
            durationMillis = style.animationDurationMs(if (revealed) FadeInMs else FadeOutMs),
            delayMillis    = if (revealed) 0 else IdleFadeDelayMs,
        ),
        label = "nxScrollbarAlpha",
    )
    return ScrollbarStyle(
        minimalHeight       = MinLength,
        thickness           = Thickness,
        shape               = RoundedCornerShape(style.buttonCorner),
        hoverDurationMillis = style.animationDurationMs(HoverCrossfadeMs),
        unhoverColor        = base.copy(alpha = 0.40f * alpha),
        hoverColor          = base.copy(alpha = 0.75f * alpha),
    )
}
