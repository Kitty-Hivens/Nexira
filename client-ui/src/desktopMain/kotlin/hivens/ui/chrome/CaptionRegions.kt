package hivens.ui.chrome

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import hivens.window.CaptionRegions

/** Marks this element's bounds as a place a press moves the window, for as long as it is composed. */
fun Modifier.captionRegion(regions: CaptionRegions?): Modifier = trackIn(regions, exclusion = false)

/** Marks this element's bounds as taking its own clicks inside a [captionRegion]. */
fun Modifier.captionExclusion(regions: CaptionRegions?): Modifier = trackIn(regions, exclusion = true)

private fun Modifier.trackIn(regions: CaptionRegions?, exclusion: Boolean): Modifier {
    if (regions == null) return this
    return composed {
        val key = remember { Any() }
        DisposableEffect(regions, key) { onDispose { regions.put(key, null, exclusion) } }
        onGloballyPositioned {
            val b = it.boundsInWindow()
            regions.put(key, CaptionRegions.Area(b.left, b.top, b.right, b.bottom), exclusion)
        }
    }
}
