package hivens.ui.activity

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hold a row to start selecting, keep holding and move to select the run you
 * drag across, tap to pick one more once a selection is running.
 *
 * One gesture rather than a tap detector and a drag detector side by side. Both
 * would compete for the same press, and which of them consumed it would decide
 * whether a hold selected anything -- a race whose outcome depends on timing the
 * user cannot see.
 *
 * The direction is decided by what the drag begins on, which is the rule people
 * already know from picking messages: starting on an unselected row paints the
 * run selected, starting on a selected one clears it. Without that, dragging
 * back over your own path toggles everything twice and lands where it started.
 *
 * It lives on the list rather than on the rows because a drag has to be followed
 * across children, and a row only ever hears about itself.
 */
@Composable
internal fun Modifier.dragSelect(
    listState: LazyListState,
    /** Index -> the identity the caller tracks. Null for a row that is not there. */
    keyAt: (Int) -> String?,
    isSelected: (String) -> Boolean,
    setSelected: (String, Boolean) -> Unit,
    /** Whether a selection is already running, which is what makes a tap mean pick. */
    selecting: Boolean,
): Modifier = composed {
    val scope = rememberCoroutineScope()
    // Read through the latest composition, never keyed on. Keying on "is a
    // selection running" meant the first hold flipped that flag, Compose restarted
    // the block, and the drag was cancelled by its own first pick -- so only the
    // second drag of a session appeared to work. Reading them this way also keeps
    // a long-lived gesture from acting on a stale set of what is already selected.
    val currentKeyAt by rememberUpdatedState(keyAt)
    val currentIsSelected by rememberUpdatedState(isSelected)
    val currentSetSelected by rememberUpdatedState(setSelected)
    val currentSelecting by rememberUpdatedState(selecting)

    pointerInput(listState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var autoScroll: Job? = null

            // A press that lifts before the hold threshold is a tap. It picks only
            // while a selection is running: outside one it belongs to whatever the
            // row itself does with a click.
            val liftedEarly = try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
                true
            } catch (_: PointerEventTimeoutCancellationException) {
                false
            }

            if (liftedEarly) {
                if (currentSelecting) {
                    indexAt(listState, down.position)?.let { index ->
                        currentKeyAt(index)?.let { key -> currentSetSelected(key, !currentIsSelected(key)) }
                    }
                }
                return@awaitEachGesture
            }

            // Held. The row under the finger decides both the first pick and what
            // the rest of the drag does.
            var lastIndex = indexAt(listState, down.position) ?: return@awaitEachGesture
            val startKey = currentKeyAt(lastIndex) ?: return@awaitEachGesture
            val paint = paintValue(currentIsSelected(startKey))
            currentSetSelected(startKey, paint)

            try {
                while (true) {
                    // Initial pass, so the movement is claimed before the list's
                    // own scroll sees it. On the main pass the scrollable has
                    // already taken the drag and turned it into scrolling, which
                    // is why holding and moving selected only the row it started
                    // on.
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()

                    val index = indexAt(listState, change.position)
                    if (index != null) {
                        // Everything between the last row and this one, not only the
                        // one under the pointer. A pointer moving faster than the
                        // event rate steps over rows, and a run with holes in it is
                        // not the run the user drew.
                        for (i in between(lastIndex, index)) {
                            currentKeyAt(i)?.let { key -> currentSetSelected(key, paint) }
                        }
                        lastIndex = index
                    }

                    // Rows below the fold are still part of the run the user is
                    // drawing; without this the selection stops at the edge of what
                    // happens to be on screen.
                    autoScroll?.cancel()
                    autoScroll = edgeScroll(scope, listState, change.position, size.height)
                }
            } finally {
                autoScroll?.cancel()
            }
        }
    }
}

/**
 * Which row the pointer is over. Falls to the nearest one when the pointer sits
 * in the space between two rows: a gap belongs to no item, so answering nothing
 * there drops a press that began in one and stalls a drag passing through.
 */
private fun indexAt(listState: LazyListState, position: Offset): Int? {
    val y = position.y.toInt()
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return null
    visible.firstOrNull { y >= it.offset && y < it.offset + it.size }?.let { return it.index }
    return visible.minByOrNull { abs(it.offset + it.size / 2 - y) }?.index
}

/** Every index from [from] to [to], whichever way round they arrived. */
internal fun between(from: Int, to: Int): IntRange = if (to >= from) from..to else to..from

/**
 * How hard to scroll for a pointer at [y] in a viewport [viewportHeight] tall:
 * negative towards the start, positive towards the end, zero away from both ends.
 *
 * Ramped rather than fixed. One speed is either too slow to cross a list of a
 * hundred mods or too fast to stop on the row you were aiming at, and which of
 * the two depends on the list, so neither value is right.
 */
internal fun edgeScrollRate(y: Float, viewportHeight: Int, band: Float = EDGE_BAND_PX): Float {
    if (viewportHeight <= 0 || band <= 0f) return 0f
    // A viewport shorter than two bands would have them overlap in the middle and
    // scroll in both directions at once; halve them so they meet and no more.
    val edge = minOf(band, viewportHeight / 2f)
    val fromTop = y
    val fromBottom = viewportHeight - y
    return when {
        fromTop < edge -> -(1f - (fromTop.coerceAtLeast(0f) / edge))
        fromBottom < edge -> (1f - (fromBottom.coerceAtLeast(0f) / edge))
        else -> 0f
    }
}

/** Runs [edgeScrollRate] until cancelled. Null when the pointer is away from both ends. */
private fun edgeScroll(
    scope: CoroutineScope,
    listState: LazyListState,
    position: Offset,
    viewportHeight: Int,
): Job? {
    val rate = edgeScrollRate(position.y, viewportHeight)
    if (abs(rate) < 0.01f) return null
    return scope.launch {
        while (isActive) {
            listState.scrollBy(rate * MAX_STEP_PX)
            delay(FRAME_MS.milliseconds)
        }
    }
}

/**
 * What a drag beginning on [startSelected] does to every row it crosses.
 *
 * Starting on an unselected row paints the run selected, starting on a selected
 * one clears it -- the rule from picking messages. The alternative, toggling each
 * row as it is crossed, means dragging back over your own path undoes it and the
 * gesture lands where it started.
 */
internal fun paintValue(startSelected: Boolean): Boolean = !startSelected

/** How close to an end the pointer has to be before the list starts moving. */
private const val EDGE_BAND_PX = 96f

/** Pixels per frame at the very edge. */
private const val MAX_STEP_PX = 14f

private const val FRAME_MS = 16L
