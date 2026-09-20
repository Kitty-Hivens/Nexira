package hivens.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene

/**
 * Advances a scene's clock until it stops asking to be redrawn.
 *
 * Every render probe here pumps frames before it takes its picture, because one
 * render photographs the first composition: a spinner where the list will be, a
 * card before its art arrives, a chrome fade at zero. The count was a round
 * number chosen to be safely past that, and the cost of a round number is paid
 * every run: the heaviest probe settled on its second frame and rendered
 * thirty-eight more, each one a full 2560 by 1440 software raster.
 *
 * So the clock still advances [frames] times and still sleeps [sleepMs] between
 * them, which is what gives work dispatched to another thread its chance to come
 * back. What stops is the drawing. Once the scene has gone [quiet] frames without
 * asking for anything, nothing more is rendered, and if something does wake up
 * later the loop picks the drawing back up. The wall-clock grace is unchanged and
 * the rasters are not.
 *
 * Returns the frame time to take the final picture at, so a caller's own
 * `render(t)` lands on the same clock the loop left off at.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun ImageComposeScene.settle(frames: Int, sleepMs: Long = 0L, quiet: Int = 4): Long {
    var t = 0L
    var still = 0
    repeat(frames) {
        if (still < quiet || hasInvalidations()) {
            render(t).close()
            still = if (hasInvalidations()) 0 else still + 1
        }
        t += FRAME_NANOS
        if (sleepMs > 0) Thread.sleep(sleepMs)
    }
    return t
}

/** One frame at sixty hertz, which is the step every probe here was already using. */
const val FRAME_NANOS = 16_000_000L
