package hivens.ui.widgets.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import hivens.ui.systemGestureExcluded
import hivens.ui.theme.NxInk
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.surface.NxSurface
import hivens.ui.surface.SurfaceKind

/**
 * The right region on a window with no room for it beside the content: it keeps no width in
 * the row, and when it is open it lies over the content from the right edge, at the width the
 * region is set to.
 *
 * The same signal that folds and unfolds the rail on a desktop drives it, so whatever fires that
 * signal (a chord, a button in the top bar) opens it here too, and the region's own props still
 * size and surface it.
 */
@Composable
internal fun RightRailOverlay(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    panelWidth: Dp,
    opacity: Float?,
    blurDp: Float,
    bottomInset: Dp,
    endInset: Dp,
    panel: @Composable () -> Unit,
) {
    // 0 is shut and 1 is fully out. A swipe in from the right edge pulls it out under the
    // finger and snaps on release, the way the rail in the row follows the pointer on a
    // desktop, so the two read as one control. Back out the same way, or by the signal.
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, tween(220)) }

    val shape = RoundedCornerShape(topStart = 16.dp)
    val width = panelWidth + endInset
    // Zero width in the row, placed by hand from its own left edge (which is the window's right
    // edge) so it can lie over the centre. An oversized child of a zero-width parent is centred
    // whatever the alignment says, so it is not left to that.
    Box(
        Modifier.width(0.dp).fillMaxHeight().layout { measurable, constraints ->
            val w = width.roundToPx()
            val placeable = measurable.measure(Constraints.fixed(w + EDGE_WIDTH.roundToPx(), constraints.maxHeight))
            layout(0, placeable.height) { placeable.place((-(w * progress.value) - EDGE_WIDTH.roundToPx()).roundToInt(), 0) }
        },
    ) {
        // The catch at the window edge: invisible, and it rides out with the panel.
        Box(
            Modifier.align(Alignment.CenterStart).width(EDGE_WIDTH).height(EDGE_HEIGHT).systemGestureExcluded().pointerInput(Unit) {
                val widthPx = width.toPx()
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        scope.launch { progress.snapTo((progress.value - delta / widthPx).coerceIn(0f, 1f)) }
                    },
                    onDragEnd = {
                        if (progress.value > 0.5f) onOpen() else scope.launch { progress.animateTo(0f, tween(160)) }
                    },
                    onDragCancel = { scope.launch { progress.animateTo(if (open) 1f else 0f, tween(160)) } },
                )
            },
        )
        if (progress.value > 0f || open) {
            NxSurface(
                SurfaceKind.Panel,
                Modifier.padding(start = EDGE_WIDTH).fillMaxSize().clip(shape),
                shape,
                opacity = opacity, blurDp = blurDp,
            ) {
                Box(Modifier.fillMaxSize().padding(end = endInset, bottom = bottomInset)) { panel() }
                // A strip of its own for closing: the panel's contents take horizontal swipes
                // (a notification is dismissed with one), so a drag over them would be theirs.
                Box(
                    Modifier.align(Alignment.CenterStart).width(GRAB_WIDTH).fillMaxHeight().pointerInput(Unit) {
                        val widthPx = width.toPx()
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                scope.launch { progress.snapTo((progress.value - delta / widthPx).coerceIn(0f, 1f)) }
                            },
                            onDragEnd = {
                                if (progress.value < 0.5f) onClose() else scope.launch { progress.animateTo(1f, tween(160)) }
                            },
                            onDragCancel = { scope.launch { progress.animateTo(if (open) 1f else 0f, tween(160)) } },
                        )
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp)).background(NxInk.quiet.copy(alpha = 0.4f)))
                }
            }
        }
    }
}

private val GRAB_WIDTH = 20.dp

/** The system leaves an app 200dp of an edge at most, so the catch is that tall and centred. */
private val EDGE_HEIGHT = 200.dp

/** How far in from the window edge a swipe has to start to pull the panel out. */
private val EDGE_WIDTH = 20.dp
