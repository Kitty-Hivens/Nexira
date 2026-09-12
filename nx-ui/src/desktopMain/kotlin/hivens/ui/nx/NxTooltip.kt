package hivens.ui.nx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.Motion
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Where a tooltip sits relative to what raised it.
 *
 * Not a cosmetic choice. A tooltip that follows the cursor cannot be entered,
 * because moving towards it moves it, so anything a reader would want to dwell
 * on or click has to be anchored instead. [NxTooltipBehaviour] pairs the two so
 * a call site cannot ask for a picture that runs away from the pointer.
 */
enum class NxTooltipPlacement { FollowCursor, BelowAnchor }

/**
 * How a tooltip behaves, as one value rather than four loose parameters.
 *
 * The named instances are the pairings that work: a label appears fast at the
 * cursor and dies with it, an explanation waits longer and holds still, a preview
 * waits longer still and can be entered. Any field can be overridden with `copy`
 * when a place genuinely differs, which is the point of a data class here, but the
 * starting point is a set that already agrees with itself.
 */
@Immutable
data class NxTooltipBehaviour(
    val delayMillis: Int,
    val placement: NxTooltipPlacement,
    val maxWidth: Dp,
    /**
     * Inset around whatever the tooltip draws. Part of the pairing rather than a
     * loose default, because a line of text and a picture want different frames
     * and getting that wrong is what makes a rich tooltip look cramped.
     */
    val contentPadding: PaddingValues,
    /**
     * Whether the tooltip survives the pointer leaving the anchor for as long as
     * the pointer is over the tooltip itself. Meaningless while the tooltip runs
     * from the cursor, which is why the presets never combine the two.
     */
    val enterable: Boolean = false,
) {
    companion object {
        /**
         * Names the thing under the pointer. One line, no dwell.
         *
         * Anchored, not at the cursor. The reveal case decides it: a caption cut by
         * an ellipsis raises the SAME text in full, and a copy of a label that runs
         * away from the pointer instead of lining up under the one it completes
         * reads as a different thing entirely. It also puts the tooltip where every
         * other floating layer in the library goes, which is under its trigger.
         */
        val Label = NxTooltipBehaviour(
            delayMillis = 400,
            placement = NxTooltipPlacement.BelowAnchor,
            maxWidth = 280.dp,
            contentPadding = PaddingValues(horizontal = Spacing.s8, vertical = Spacing.s4),
        )

        /**
         * The same label, following the pointer. For a target with no useful bounds
         * to hang off, a long row or a canvas where the anchor is the whole area.
         */
        val LabelAtCursor = Label.copy(placement = NxTooltipPlacement.FollowCursor)

        /** Explains it. Anchored, so it stays still while it is read. */
        val Explain = NxTooltipBehaviour(
            delayMillis = 600,
            placement = NxTooltipPlacement.BelowAnchor,
            maxWidth = 360.dp,
            contentPadding = PaddingValues(horizontal = Spacing.s12, vertical = Spacing.s10),
            enterable = true,
        )

        /** Shows it. A picture earns a longer wait and more room. */
        val Preview = NxTooltipBehaviour(
            delayMillis = 700,
            placement = NxTooltipPlacement.BelowAnchor,
            maxWidth = 320.dp,
            contentPadding = PaddingValues(Spacing.s10),
            enterable = true,
        )
    }
}

/**
 * Hover tooltip carrying whatever [tooltip] draws.
 *
 * Built on [Popup] rather than Compose's `TooltipArea` for two reasons the
 * parameters above depend on: that one places at the cursor and nothing else, and
 * it offers no transition, so a tooltip appeared at full size while every other
 * floating layer in the library grows out of what raised it.
 *
 * [enabled] false renders [content] bare with no hover machinery, and keeps the
 * caller's modifier identical across the flip so toggling never re-lays-out the
 * content. Call sites gate on real need, e.g. a caption that is not truncated has
 * nothing to reveal.
 */
@Composable
fun NxTooltip(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behaviour: NxTooltipBehaviour = NxTooltipBehaviour.Label,
    shape: Shape = MaterialTheme.shapes.extraSmall,
    contentPadding: PaddingValues = behaviour.contentPadding,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }

    val anchorInteraction = remember { MutableInteractionSource() }
    val tooltipInteraction = remember { MutableInteractionSource() }
    val overAnchor by anchorInteraction.collectIsHoveredAsState()
    val overTooltip by tooltipInteraction.collectIsHoveredAsState()
    val wanted = overAnchor || (behaviour.enterable && overTooltip)

    var shown by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(Offset.Zero) }

    // The delay belongs to the appearance only. Hiding is immediate, because a
    // tooltip that lingers after the pointer has moved on is a tooltip in the way.
    LaunchedEffect(wanted, behaviour.delayMillis) {
        if (!wanted) {
            shown = false
            return@LaunchedEffect
        }
        // Zero means now, not "suspend for zero". A delay of nothing still parks the
        // coroutine until the dispatcher comes back round, which is a frame the
        // caller did not ask to wait for.
        if (behaviour.delayMillis > 0) kotlinx.coroutines.delay(behaviour.delayMillis.toLong())
        shown = true
    }

    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }

    Box(
        modifier
            .hoverable(anchorInteraction)
            .pointerInput(behaviour.placement) {
                if (behaviour.placement != NxTooltipPlacement.FollowCursor) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) {
                            event.changes.firstOrNull()?.let { cursor = it.position }
                        }
                    }
                }
            },
    ) {
        content()

        val states = remember { MutableTransitionState(false) }
        states.targetState = shown
        if (states.currentState || states.targetState) {
            val cursorX = cursor.x.roundToInt()
            val cursorY = cursor.y.roundToInt()
            val provider: PopupPositionProvider = when (behaviour.placement) {
                NxTooltipPlacement.FollowCursor ->
                    remember(cursorX, cursorY, gapPx) { AtCursorPoint(cursorX, cursorY, gapPx) }
                NxTooltipPlacement.BelowAnchor ->
                    remember(gapPx) { BelowAnchorStartAligned(gapPx) }
            }
            Popup(
                popupPositionProvider = provider,
                // Not focusable: a tooltip never takes the keyboard, and a focusable
                // popup would steal it from the field the pointer is only passing over.
                properties = PopupProperties(focusable = false),
            ) {
                AnimatedVisibility(
                    visibleState = states,
                    enter = fadeIn(Motion.tap.of()) +
                        scaleIn(Motion.tap.of(), initialScale = 0.92f, transformOrigin = TransformOrigin(0f, 0f)),
                    exit = fadeOut(Motion.tap.of()) +
                        scaleOut(Motion.tap.of(), targetScale = 0.94f, transformOrigin = TransformOrigin(0f, 0f)),
                ) {
                    NxSurface(
                        level = NxSurfaceLevel.Floating,
                        blurDp = 0f,
                        // Opaque for the same reason a menu is: it floats over
                        // arbitrary content, and a translucent body would read
                        // whatever it happens to cover.
                        opacity = 1f,
                        shape = shape,
                        shadowDp = 6f,
                        modifier = Modifier.hoverable(tooltipInteraction, enabled = behaviour.enterable),
                    ) {
                        Box(Modifier.widthIn(max = behaviour.maxWidth).padding(contentPadding)) { tooltip() }
                    }
                }
            }
        }
    }
}

/**
 * The plain case: a line of text naming what the pointer is on.
 *
 * Kept as its own overload rather than a default argument so the forty-character
 * call sites stay one line, and so the common tooltip cannot accidentally be given
 * a behaviour that suits a picture.
 */
@Composable
fun NxTooltip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    behaviour: NxTooltipBehaviour = NxTooltipBehaviour.Label,
    content: @Composable () -> Unit,
) = NxTooltip(
    tooltip = { NxTooltipLabel(text) },
    modifier = modifier,
    enabled = enabled,
    behaviour = behaviour,
    content = content,
)

// ── Contents ────────────────────────────────────────────────────────────────
// Four kinds, each designed once here rather than assembled at every call site.
// They are ordinary composables, so a place with a genuinely different need still
// passes its own; these exist so that the common ones do not drift apart.

/** One line. What the current tooltip has always drawn. */
@Composable
fun NxTooltipLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = NxTheme.colors.textPrimary,
    )
}

/** A glyph and a line, for a control whose icon is its only label. */
@Composable
fun NxTooltipGlyphLabel(icon: IconKey, text: String) {
    // Top, not centre: a label long enough to wrap would otherwise park the glyph
    // against the middle of the block, pointing at the gap between its lines.
    Row(verticalAlignment = Alignment.Top) {
        Symbol(icon, contentDescription = null, tint = NxTheme.colors.textSecondary, size = 16.dp)
        Spacer(Modifier.width(Spacing.s8))
        NxTooltipLabel(text)
    }
}

/**
 * A title with an explanation under it, and optionally the keystroke that does the
 * same thing. A menu is where a shortcut is discovered, and so is this: someone
 * hovering for an explanation is by definition someone who does not know it yet.
 */
@Composable
fun NxTooltipTitled(title: String, description: String, shortcut: String? = null) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = NxTheme.colors.textPrimary,
            )
            if (shortcut != null) {
                Spacer(Modifier.width(Spacing.s12))
                Text(
                    text = shortcut,
                    style = MaterialTheme.typography.labelSmall,
                    color = NxTheme.colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
    }
}

/**
 * A picture with a caption under it. The caller draws the picture, because what it
 * is differs everywhere this is wanted: a skin, a pack's art, a widget's preview.
 */
@Composable
fun NxTooltipPreview(caption: String? = null, picture: @Composable () -> Unit) {
    Column {
        picture()
        if (caption != null) {
            Spacer(Modifier.height(Spacing.s6))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * Opens with its top-left at the pointer, offset clear of the cursor itself,
 * clamped on-screen and flipped above near the window bottom.
 *
 * The offset arrives in ANCHOR coordinates, which is what a pointer event
 * reports, so the anchor's own window position is added back here.
 */
private class AtCursorPoint(
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
        val px = (anchorBounds.left + x).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.top + y + gapPx * 2
        val py = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top + y - popupContentSize.height - gapPx).coerceAtLeast(0)
        }
        return IntOffset(px, py)
    }
}

/**
 * Anchors the tooltip's left edge to the trigger's left edge, just below it, and
 * flips above when it would overrun the window bottom. The menu's own provider
 * aligns to the trigger's RIGHT edge because an overflow button opens leftwards;
 * a tooltip reads with its subject, so it starts where the subject starts.
 */
private class BelowAnchorStartAligned(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gapPx
        val y = if (below + popupContentSize.height <= windowSize.height) {
            below
        } else {
            (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}
